import System._
import collection._

import java.io.File

import ducttape._
import ducttape.hyperdag._
import ducttape.Types._
import ducttape.syntax.AbstractSyntaxTree._
import ducttape.syntax.GrammarParser
import ducttape.workflow._
import ducttape.util._

package ducttape {
  class Config {
    var headerColor = Console.BLUE
    var byColor = Console.BLUE
    var taskColor = Console.GREEN
    var errorColor = Console.RED
    var resetColor = Console.RESET

    var errorLineColor = Console.BLUE // file and line number of error
    var errorScriptColor = Console.WHITE // quote from file

    var taskNameColor = Console.GREEN
    var realNameColor = Console.BLUE
  }
}

object Ducttape {

  def main(args: Array[String]) {
    val conf = new Config

    err.println("%sDuctTape v0.1".format(conf.headerColor))
    err.println("%sBy Jonathan Clark".format(conf.byColor))
    err.println(Console.RESET)

    def usage() {
      err.println("""
Usage: ducctape workflow.tape
  --purge
  --viz
  --env taskName realName
  --markDone taskName realNames...
  --invalidate taskName realNames...
""")
      exit(1)
    }
    if(args.length == 0) usage()

    val mode: String = args.length match {
      case 1 => "execute"
      case _ => args(1) match {
        case "--list" => "list"
        case "--purge" => "purge"
        case "--viz" => "viz"
        case "--viz-debug" => "viz-debug"
        case "--env" => "env"
        case "--markDone" => "markDone"
        case "--invalidate" => "invalidate"
        case _ => usage(); ""
      }
    }

    // format exceptions as nice error messages
    def ex2err[T](func: => T): T = {
      import ducttape.syntax.FileFormatException
      try { func } catch {
        case e: FileFormatException => {
          err.println("%sERROR: %s%s".format(conf.errorColor, e.getMessage, conf.resetColor))
          for( (file: File, line: Int, col: Int, untilLine: Int) <- e.refs) {
            err.println("%s%s:%d%s".format(conf.errorLineColor, file.getAbsolutePath, line, conf.resetColor))
            val badLines = io.Source.fromFile(file).getLines.drop(line-1).take(line-untilLine+1)
            err.println(conf.errorScriptColor + badLines.mkString("\n"))
            err.println(" " * (col-2) + "^")
          }
          exit(1)
          throw new Error("Unreachable") // make the compiler happy
        }
        case e: Exception => {
          err.println("%sERROR: %s".format(conf.errorColor, e.getMessage))
          exit(1)
          throw new Error("Unreachable") // make the compiler happy
        }
        case t: Throwable => throw t
      }
    }

    // make these messages optional with verbosity levels?
    var file = new File(args(0))
    //println("Reading workflow from %s".format(file.getAbsolutePath))
    val wd: WorkflowDefinition = ex2err(GrammarParser.read(file))
    //println("Building workflow...")
    val workflow: HyperWorkflow = ex2err(WorkflowBuilder.build(wd))
    //println("Workflow contains %d tasks".format(workflow.dag.size))
    
    // TODO: Check that all input files exist

    val baseDir = file.getAbsoluteFile.getParentFile
    val dirs = new DirectoryArchitect(baseDir)

    def colorizeDirs(list: Traversable[(String,String)]): Seq[String] = {
      list.toSeq.map{case (name, real) => {
        "%s/%s%s%s/%s%s%s".format(baseDir.getAbsolutePath,
                                  conf.taskNameColor, name, conf.resetColor,
                                  conf.realNameColor, real, conf.resetColor)
      }}
    }

    mode match {
      case "list" => {
        for(v: UnpackedWorkVert <- workflow.unpackedWalker.iterator) {
          val taskT: TaskTemplate = v.packed.value
          val task: RealTask = taskT.realize(v)
          println("%s %s".format(task.name, task.realizationName))
          //println("Actual realization: " + v.realization)
        }
      }
      case "purge" => {
        // TODO: Confirm this drastic action
        val visitor: PackedDagVisitor = new Purger(conf, dirs)
        for(v: PackedWorkVert <- workflow.packedWalker.iterator) {
          visitor.visit(v.value)
        }
      }
      case "env" => {
        // TODO: Apply filters so that we do much less work to get here
        // TODO: Complain if wrong number of args
        val goalTaskName = args(2)
        val goalRealName = args(3)
        for(v: UnpackedWorkVert <- workflow.unpackedWalker.iterator) {
          val taskT: TaskTemplate = v.packed.value
          if(taskT.name == goalTaskName) {
            val task: RealTask = taskT.realize(v)
            if(task.realizationName == goalRealName) {
              val env = new TaskEnvironment(dirs, task)
              for( (k,v) <- env.env) {
                println("%s=%s".format(k,v))
              }
            }
          }
        }
      }
      case "markDone" => {
        // TODO: Apply filters so that we do much less work to get here
        val goalTaskName = args(2)
        val goalRealNames = args.toList.drop(3).toSet
        for(v: UnpackedWorkVert <- workflow.unpackedWalker.iterator) {
          val taskT: TaskTemplate = v.packed.value
          if(taskT.name == goalTaskName) {
            val task: RealTask = taskT.realize(v)
            if(goalRealNames(task.realizationName)) {
              val env = new TaskEnvironment(dirs, task)
              if(CompletionChecker.isComplete(env)) {
                err.println("Task already complete: " + task.name + "/" + task.realizationName)
              } else {
                CompletionChecker.forceCompletion(env)
                err.println("Forced completion of task: " + task.name + "/" + task.realizationName)
              }
            }
          }
        }
      }
      case "execute" => {
        def visitAll(visitor: UnpackedDagVisitor, numCores: Int = 1) {
          workflow.unpackedWalker.foreach(numCores, { v: UnpackedWorkVert => {
            val taskT: TaskTemplate = v.packed.value
            val task: RealTask = taskT.realize(v)
            visitor.visit(task)
          }})
        }

        err.println("Checking for completed steps...")
        val cc = new CompletionChecker(conf, dirs)
        visitAll(cc)

        err.println("About to run the following tasks:")
        err.println(colorizeDirs(cc.todo).mkString("\n"))

        if(cc.partial.size > 0) {
          err.println("About to permenantly delete the partial output in the following directories:")
          err.println(colorizeDirs(cc.partial).mkString("\n"))
          err.print("Are you sure you want to DELETE all these? [y/n] ") // user must still press enter
        } else {
          err.print("Are you sure you want to run all these? [y/n] ") // user must still press enter
        }

        Console.readChar match {
          case 'y' | 'Y' => {
            err.println("Removing partial output...")
            visitAll(new PartialOutputRemover(conf, dirs, cc.partial))
            err.println("Retreiving code and building...")
            visitAll(new Builder(conf, dirs, cc.todo))
            err.println("Executing tasks...")
            val numCores = 2 // j=2 for testing
            visitAll(new Executor(conf, dirs, workflow, cc.completed, cc.todo), numCores)
          }
          case _ => err.println("Doing nothing")
        }

      }
      case "viz" => {
        err.println("Generating GraphViz dot visualization...")
        import ducttape.viz._
        println(GraphViz.compileXDot(WorkflowViz.toGraphViz(workflow)))
      }
      case "viz-debug" => {
        err.println("Generating GraphViz dot visualization of MetaHyperDAG...")
        import ducttape.viz._
        println(workflow.dag.toGraphVizDebug)
      }
      case "invalidate" => {
        val taskToKill = args(2)
        val realsToKill = args.toList.drop(3).toSet
        err.println("Invalidating task %s for realizations: %s".format(taskToKill, realsToKill))

        // 1) Accumulate the set of changes
        // we'll have to keep a map of parents and use
        // the dag to keep state
        val victims = new mutable.HashSet[(String,String)]
        val victimList = new mutable.ListBuffer[(String,String)]
        for(v: UnpackedWorkVert <- workflow.unpackedWalker.iterator) {
          val taskT: TaskTemplate = v.packed.value
          val task: RealTask = taskT.realize(v)
          if(taskT.name == taskToKill) {
            if(realsToKill(task.realizationName)) {
              //err.println("Found victim %s/%s".format(taskT.name, task.realizationName))
              // TODO: Store seqs instead?
              victims += ((task.name, task.realizationName))
              victimList += ((task.name, task.realizationName))
            }
          } else {
            // was this task invalidated by its parent?
            // TODO: Can we propagate this in a more natural way
            val isVictim = task.inputVals.exists{ case (_, _, srcTaskDef, srcRealization) => {
              val parent = (srcTaskDef.name, Task.realizationName(Task.branchesToMap(srcRealization)))
              victims(parent)
            }}
            if(isVictim) {
              //err.println("Found indirect victim %s/%s".format(task.name, task.realizationName))
              victims += ((task.name, task.realizationName))
              victimList += ((task.name, task.realizationName))
            }
          }
        }

        // 2) prompt the user
        // TODO: Increment version instead of deleting?
        err.println("About to permenantly delete the following directories:")
        // TODO: Use directory architect here
        val absDirs = victimList.map{case (name, real) => new File(baseDir, "%s/%s".format(name,real))}
        err.println(colorizeDirs(victimList).mkString("\n"))

        err.print("Are you sure you want to delete all these? [y/n] ") // user must still press enter
        Console.readChar match {
          case 'y' | 'Y' => absDirs.foreach(f => { err.println("Deleting %s".format(f.getAbsolutePath)); Files.deleteDir(f) })
          case _ => err.println("Doing nothing")
        }
      }
    }
  }
}
