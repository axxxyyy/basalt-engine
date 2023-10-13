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
package basalt.core.descriptor

import basalt.core.archetype.{ArchetypeId, ComponentArchetype}
import basalt.core.collection.GenerationalVector
import basalt.core.datatype.{ComponentSet, ComponentId, EntityId}

import collection.mutable.{LongMap, HashMap}

import cats.effect.kernel.Sync
import cats.effect.std.AtomicCell
import cats.syntax.all._
import cats.effect.kernel.Async

/** Information required by the ongoing ticking and querying processes,
  * available at runtime execution, dynamically created by an
  * [[basalt.core.engine.Engine]].
  */
class ArchetypesDescriptor[F[_]: Sync](
    val counter: AtomicCell[F, ArchetypeId],
    val byComponentSet: HashMap[ComponentSet, ComponentArchetype[F]],
    val componentIndex: LongMap[Set[ArchetypeId]],
):
  val archetypes: LongMap[ComponentArchetype[F]] =
    LongMap(0L -> ComponentArchetype[F](0, ComponentSet.empty, apply))

  def init(components: ComponentSet): F[ComponentArchetype[F]] =
    byComponentSet
      .get(components)
      .fold(
        for
          id <- counter.getAndUpdate(_ + 1)
          archetype = ComponentArchetype[F](id, components, apply)
          _ = archetypes.put(id, archetype)
          _ = byComponentSet.put(components, archetype)
          _ = for component <- components.toSet do
            val current = componentIndex.getOrElse(component, Set[ComponentId]())
            componentIndex.update(component.toLong, )
        yield archetype
      )(Sync[F].pure)

  def apply(components: ComponentSet): F[ComponentArchetype[F]] =
    byComponentSet.get(components) match
      case Some(archetype) => Sync[F].pure(archetype)
      case None            => init(components)

  def apply(id: ArchetypeId): Option[ComponentArchetype[F]] =
    archetypes.get(id)

  def lift(id: ArchetypeId): F[ComponentArchetype[F]] =
    Sync[F].fromOption(
      archetypes.get(id),
      new NoSuchElementException(s"Archetype $id not found")
    )

  def getMatching(components: ComponentSet): Iterable[ComponentArchetype[F]] =
    val iter = components.iterator
    byComponentSet.values.filter { archetype =>
      val iterator = archetype.components.iterator
      iter.forall(iterator.contains)
    }

  def switchFor(
      entity: EntityId,
      from: ArchetypeId,
      to: ArchetypeId
  ): F[Unit] =
    for
      archetypes <- Sync[F].pure((archetypes.get(from), archetypes.get(to)))
      _ <- archetypes match
        case (Some(fromArchetype), Some(toArchetype)) =>
          fromArchetype.moveEntity(toArchetype, entity)
        case _ => Sync[F].unit
    yield ()

  def removeEntity(entity: EntityId, archetype: ArchetypeId): F[Unit] =
    archetypes.get(archetype).fold(Sync[F].unit)(_.removeEntity(entity))

object ArchetypesDescriptor:
  def apply[F[_]: Async]: F[ArchetypesDescriptor[F]] =
    for
      counter <- AtomicCell[F].of[ArchetypeId](1L)
      byComponentSet = HashMap.empty[ComponentSet, ComponentArchetype[F]]
    yield new ArchetypesDescriptor[F](counter, byComponentSet)
