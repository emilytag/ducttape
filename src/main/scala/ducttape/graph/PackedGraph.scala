package ducttape.graph


import ducttape.syntax.AbstractSyntaxTree._

class PackedGraph(val wd: WorkflowDefinition) extends Graph {

  val vertices = PackedGraph.process(wd)

  System.out.println(Dot2texi.latexPoster(vertices))

}


object PackedGraph {

	val isTaskVertex   : PartialFunction[Vertex,TaskVertex]        = { case vertex:TaskVertex        => vertex }
//  val isConfigVertex : PartialFunction[Vertex,ConfigParamVertex] = { case vertex:ConfigParamVertex => vertex }
//
//  val isInputVertex  : PartialFunction[Vertex,TaskInputVertex]   = { case vertex:TaskInputVertex   => vertex }
//  val isParamVertex  : PartialFunction[Vertex,TaskParamVertex]   = { case vertex:TaskParamVertex   => vertex }
//  val isOutputVertex : PartialFunction[Vertex,TaskOutputVertex]  = { case vertex:TaskOutputVertex  => vertex }

  val isTaskSpecVertex  : PartialFunction[Vertex,TaskSpecVertex] = { case vertex:TaskSpecVertex => vertex }

  val isVariableRef     : PartialFunction[Vertex, VariableReferenceVertex] = { case vertex:VariableReferenceVertex => vertex }

  val isPackageSpec     : PartialFunction[Vertex, PackageSpecVertex] = { case vertex:PackageSpecVertex => vertex }
  val isPackageVertex   : PartialFunction[Vertex, PackageVertex] = { case vertex:PackageVertex => vertex }

  val isSummaryOfVertex : PartialFunction[Vertex, SummaryOfVertex] = { case vertex:SummaryOfVertex => vertex }

  private def createMap[V <: Vertex](vertices:Seq[Vertex], selectionFunction:PartialFunction[Vertex,V]) : Map[String,Set[V]] = {

    val subsetOfVertices = vertices.collect(selectionFunction)

    val names = subsetOfVertices.map{ vertex => vertex.toString() }

    val tuples = names.zip(subsetOfVertices)
    val result : Map[String,Set[V]] = {

      import scala.collection.mutable.{HashMap, Set, MultiMap}

      val mutableMultiMap = new HashMap[String, Set[V]] with MultiMap[String, V]

      tuples.foreach({ case (name, vertex) =>
        mutableMultiMap.addBinding(name, vertex)
      })

      mutableMultiMap.
        // convert each value from a mutable set to an immutable set
        map({case (key:String, value) => (key, value.toSet)}).
        // convert the mutable map to an immutable map
        toMap
    }

    return result

  }


  private def addEdges[FromVertex <: Vertex, ToVertex <: Vertex]
                      (vertices:Seq[Vertex],
                          functionToSelectFromVertices:PartialFunction[Vertex,FromVertex],
                          functionToSelectToVertices:PartialFunction[Vertex,ToVertex]
                      ) : Unit = {

    val fromMap : Map[String,Set[FromVertex]] = createMap(vertices, functionToSelectFromVertices)
    val toMap   : Map[String,Set[ToVertex]]   = createMap(vertices, functionToSelectToVertices)

    toMap.foreach({ case (key:String, valueSet:Set[ToVertex]) => {
      valueSet.foreach({ toVertex:ToVertex =>
        fromMap.get(key) match {
          case Some(fromVertexSet) => {
            if (fromVertexSet.size==1) {
              val fromVertex = fromVertexSet.seq.head
              Edge.connect(fromVertex, toVertex)
            } else {
              throw new RuntimeException("Expected exactly one parent vertex matching name %s, but found %d".format(key, fromVertexSet.size))
            }
          }
          case None => throw new RuntimeException("No parent vertex found for reference %s".format(key))
        }
      })
    }})
  }

  private def processSummaryOfVertices(vertices:Seq[Vertex]) : Seq[Vertex] = {
    val summaryOfMap : Map[String,Set[SummaryOfVertex]] = createMap(vertices, isSummaryOfVertex)
    val taskMap      : Map[String,Set[TaskVertex]]      = createMap(vertices, isTaskVertex)

    val newVertices = new scala.collection.mutable.ArrayBuffer[Vertex]()

    summaryOfMap.foreach({ case (taskName:String, valueSet:Set[SummaryOfVertex]) => {
      valueSet.foreach({ summaryOfVertex => {
        taskMap.get(taskName) match {
          case Some(taskVertexSet) => {
        	  if (taskVertexSet.size==1) {
              val taskVertex = taskVertexSet.seq.head
              taskVertex.foreachChild({ taskChild => {
                taskChild match {
                  case taskOutputVertex:TaskOutputVertex => {

                    val specName = taskOutputVertex.contents.name; //println(taskName); println(specName)

                    val taskOutputReference = new TaskVariable(taskName, specName); // println(taskOutputReference)
                    val summaryOfInput = new TaskInputSpec(specName, taskOutputReference)

                    val taskOutputReferenceVertex = new TaskVariableVertex(taskOutputReference)
                    val summaryOfInputVertex = new TaskInputVertex(summaryOfInput)

                    newVertices.append(taskOutputReferenceVertex)
                    newVertices.append(summaryOfInputVertex)

                    Edge.connect(taskOutputVertex, taskOutputReferenceVertex)
                    Edge.connect(taskOutputReferenceVertex, summaryOfInputVertex)
                    Edge.connect(summaryOfInputVertex, summaryOfVertex)
                  }
                  case _ => {}
                }
              }})
            } else {
              throw new RuntimeException("Expected exactly one parent vertex matching name %s, but found %d".format(taskName, taskVertexSet.size))
            }
          }
          case None => throw new RuntimeException("No task vertex found for summaryOf %s".format(taskName))
        }
      }})
    }})

    return newVertices.toSeq

  }


  def process(astNode:WorkflowDefinition) : Seq[Vertex] = {
    val graphFragments = new GraphFragments(astNode)

    addEdges(graphFragments.vertices, isTaskSpecVertex, isVariableRef)
    addEdges(graphFragments.vertices, isPackageVertex, isPackageSpec)

    val summaryOfInputVertices = processSummaryOfVertices(graphFragments.vertices)

    return graphFragments.vertices ++ summaryOfInputVertices
  }

}


