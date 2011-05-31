package ducttape.hyperdag

import collection._
import java.util.concurrent._

import ducttape.util._

// hedge filter allows us to exclude certain hyperedges from
// the edge derivation (e.g. edges without a realization name)
// the "edge derivation" == the realization
// TODO: SPECIFY GOAL VERTICES
class UnpackedDagWalker[V,H,E](val dag: PackedDag[V,H,E],
                               val hedgeFilter: H => Boolean,
                               val selectionFilter: MultiSet[H] => Boolean)
  extends Walker[UnpackedVertex[V,H,E]] {
    
  class ActiveVertex(val v: PackedVertex[V],
                     val e: Option[HyperEdge[H,E]]) {

    // accumulate parent realizations
    // indices: sourceEdge, whichUnpacking, whichRealization
    val filled = new Array[mutable.Seq[Seq[H]]](if(e.isEmpty) 0 else e.get.e.size)

    private def unpack(i: Int,
                       iFixed: Int,
                       combo: MultiSet[H],
                       parentReals: Array[Seq[H]],
                       callback: UnpackedVertex[V,H,E] => Unit) {

      // hedgeFilter has already been applied
      if(i == filled.size) {
        if(selectionFilter(combo)) {
          callback(new UnpackedVertex[V,H,E](v, e,
                         combo.toList, parentReals.toList))
        }
      } else if(i == iFixed) {
        unpack(i+1, iFixed, combo, parentReals, callback)
      } else {
        // for each backpointer to a realization...
        for(parentRealization: Seq[H] <- filled(i)) {
          combo ++= parentRealization
          parentReals(i) = parentRealization
          unpack(i+1, iFixed, combo, parentReals, callback)
          combo --= parentRealization
        }     
      }
    }

    // get the *new* unpackings possible based on all
    // backpointers we've saved so far, but fixing
    // one of the hyperedge sources at a particular realization
    def unpack(iFixed: Int,
               fixedRealization: Seq[H],
               callback: UnpackedVertex[V,H,E] => Unit) {
      
      val combo = new MultiSet[H]
      val parentReals = new Array[Seq[H]](filled.size)
      parentReals(iFixed) = fixedRealization
      combo ++= fixedRealization
      unpack(0, iFixed, combo, parentReals, callback)
    }
  }

  private val active = new mutable.HashMap[PackedVertex[V],ActiveVertex]
            with mutable.SynchronizedMap[PackedVertex[V],ActiveVertex]
  // TODO: dag.size might not be big enough for this unpacked version...
  private val agenda = new ArrayBlockingQueue[UnpackedVertex[V,H,E]](dag.size)
  private val taken = new mutable.HashSet[UnpackedVertex[V,H,E]] with mutable.SynchronizedSet[UnpackedVertex[V,H,E]]
  private val completed = new mutable.HashSet[UnpackedVertex[V,H,E]] with mutable.SynchronizedSet[UnpackedVertex[V,H,E]]

  // first, visit the roots, which are guaranteed not to be packed
  for(root <- dag.roots.iterator) {
    val actRoot = new ActiveVertex(root, None)
    val unpackedRoot = new UnpackedVertex[V,H,E](root, None,
                             List.empty, List.empty)
    agenda.offer(unpackedRoot)
    active += root -> actRoot
  }

  override def take(): Option[UnpackedVertex[V,H,E]] = {
    if(agenda.size == 0 && taken.size == 0) {
      return None
    } else {
      agenda.synchronized {
        val key: UnpackedVertex[V,H,E] = agenda.take
        taken += key
        return Some(key)
      }
    }
  }

  override def complete(item: UnpackedVertex[V,H,E]) = {
    require(active.contains(item.packed), "Cannot find active vertex for %s in %s".format(item, active))
    val key: ActiveVertex = active(item.packed)

    // TODO: Are elements of taken properly hashable?

    // we always lock agenda & completed jointly
    agenda.synchronized {
      taken -= item
    }

    // first, match fronteir vertices
    // note: consequent is an edge unlike the packed walker
    for(consequentE <- dag.outEdges(key.v)) {
      val consequentV = dag.sink(consequentE)
      val activeCon = active.getOrElseUpdate(consequentV,
                        new ActiveVertex(consequentV, Some(consequentE)))

      val antecedents = dag.sources(consequentE)
      for(iEdge <- 0 until activeCon.filled.size) {
        // save a backpointer to the realizations (hyperedge derivation)
        // as of this parent antecedent vertex
        if(item.packed == antecedents(iEdge)) {
          // before adding backpointer, take cross-product
          // of unpackings possible when holding this realization
          // fixed
          activeCon.unpack(iEdge, item.realization,
            (unpackedV: UnpackedVertex[V,H,E]) => {
              agenda.synchronized {
                agenda.offer(unpackedV)
                // TODO: We could sort the agenda
                // here to impose different objectives...
              }
            })
          activeCon.filled(iEdge) :+ item.realization
        }
      }
    }

    // finally visit this vertex
    completed += item
  }
}
