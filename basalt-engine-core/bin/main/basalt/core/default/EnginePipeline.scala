/** Basalt Engine, an open-source ECS engine for Scala 3 Copyright (C) 2023
  * Pedro Henrique
  *
  * This program is free software; you can redistribute it and/or modify it
  * under the terms of the GNU Lesser General Public License as published by the
  * Free Software Foundation; either version 3 of the License, or (at your
  * option) any later version.
  *
  * This program is distributed in the hope that it will be useful, but WITHOUT
  * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
  * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
  * for more details.
  *
  * You should have received a copy of the GNU Lesser General Public License
  * along with this program; if not, write to the Free Software Foundation,
  * Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
  */
package basalt.core.default

import basalt.core.datatype.{Tick, TickInfo, System}
import basalt.core.engine.Engine
import basalt.core.event.internal.{InternalEvent, InternalEvents}
import basalt.core.query.QueryingFilter

import cats.effect.kernel.{Async, Ref, Temporal}

import cats.syntax.all.*

import fs2.{Stream, Pipe}
import basalt.core.datatype.EntityId
import basalt.core.datatype.ComponentId

/** Processes entities, components, queries, and systems given their previous
  * and current state.
  *
  * @tparam F
  *   the effect type used for the engine.
  */
class EnginePipeline[F[_]: Async](
    private val engine: Engine[F],
    private val events: InternalEvents[F],
    private val systems: List[System[F]],
    tps: Int = 20
):
  private val ticking = TickInfo(tps)

  /** Intercepts the looping process in order to perform some action within the
    * current tick. Also processes events that happened during the processing of
    * the previous tick and between the previous and current tick, those should
    * be available for reading once, then discarded once the current tick is
    * processed.
    *
    * @param currentTick
    *   sequence number of the current tick. It starts at 1 and goes up to
    *   [[tps]].
    * @return
    *   the effect that is going to be performed within the current tick.
    */
  def intercept: Pipe[F, Tick, Unit] = stream =>
    stream.map { tick =>
      Stream.emits(systems).flatMap(_.execute(tick, engine))
    }.parJoinUnbounded

  /** Loops the engine pipeline to allow systems, queries, components and
    * entities to be processed.
    * @return
    *   the effect that is going to be performed within the current tick.
    */
  def loop: Stream[F, Unit] =
    Stream.eval(Ref[F].of(Tick(1))).flatMap { counter =>
      Stream
        .awakeEvery[F](ticking.tickDuration)
        .evalMap(_ =>
          counter
            .updateAndGet(before => before.validate(before + 1, ticking))
        )
        .through(intercept)
    }
