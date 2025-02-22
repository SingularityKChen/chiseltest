// SPDX-License-Identifier: Apache-2.0

package chiseltest.internal

import chisel3.experimental.BaseModule
import chisel3.reflect.DataMirror
import chisel3.{Data, Element, Module, Record, Vec}
import chiseltest.coverage.Coverage
import chiseltest.simulator.{Compiler, DebugPrintWrapper, Simulator}
import firrtl2.AnnotationSeq
import firrtl2.annotations.ReferenceTarget
import firrtl2.transforms.{CheckCombLoops, CombinationalPath}

object BackendExecutive {

  def start[T <: Module](
    dutGen:               () => T,
    testersAnnotationSeq: AnnotationSeq,
    chiselAnnos:          firrtl.AnnotationSeq
  ): BackendInstance[T] = {

    // elaborate the design
    val (highFirrtl, dut) = Compiler.elaborate(dutGen, testersAnnotationSeq, chiselAnnos)

    // extract port names
    val portNames = DataMirror.modulePorts(dut).flatMap { case (name, data) => getDataNames(name, data).toList }.toMap

    // compile to low firrtl
    val lowFirrtl = Compiler.toLowFirrtl(highFirrtl)

    // extract combinatorial loops from the LoFirrtl circuit
    val pathAnnotations = (new CheckCombLoops).execute(lowFirrtl).annotations
    val paths = pathAnnotations.collect { case c: CombinationalPath => c }
    val pathsAsData = combinationalPathsToData(dut, paths, portNames, componentToName)

    // extract coverage information
    val coverageAnnotations = Coverage.collectCoverageAnnotations(lowFirrtl.annotations)

    // create the simulation backend
    val sim = Simulator.getSimulator(testersAnnotationSeq)
    val tester = sim.createContext(lowFirrtl)

    // wrap the simulation in case we want to debug simulator interactions
    val finalTester = if (testersAnnotationSeq.contains(PrintPeekPoke)) {
      new DebugPrintWrapper(tester)
    } else { tester }

    val noThreading = testersAnnotationSeq.contains(NoThreadingAnnotation)
    if (noThreading) {
      new SingleThreadBackend(dut, portNames, pathsAsData, tester, coverageAnnotations)
    } else {
      new GenericBackend(dut, portNames, pathsAsData, finalTester, coverageAnnotations)
    }
  }

  private def componentToName(component: ReferenceTarget): String = component.name

  /** Returns a Seq of (data reference, fully qualified element names) for the input.
    * name is the name of data
    */
  private def getDataNames(name: String, data: Data): Seq[(Data, String)] = Seq(data -> name) ++ (data match {
    case _: Element => Seq()
    case b: Record  => b.elements.toSeq.flatMap { case (n, e) => getDataNames(s"${name}_$n", e) }
    case v: Vec[_]  => v.zipWithIndex.flatMap { case (e, i) => getDataNames(s"${name}_$i", e) }
  })

  /** This creates some kind of map of combinational paths between inputs and outputs.
    *
    * @param dut       use this to figure out which paths involve top level iO
    * @param paths     combinational paths found by firrtl pass CheckCombLoops
    * @param dataNames a map between a port's Data and it's string name
    * @param componentToName used to map [[ReferenceTarget]]s  found in paths into correct local string form
    * @return
    */
  //TODO: better name
  //TODO: check for aliasing in here
  //TODO graceful error message if there is an unexpected combinational path element?
  private def combinationalPathsToData(
    dut:             BaseModule,
    paths:           Seq[CombinationalPath],
    dataNames:       Map[Data, String],
    componentToName: ReferenceTarget => String
  ): Map[Data, Set[Data]] = {

    val nameToData = dataNames.map(_.swap)
    val filteredPaths = paths.filter { p => // only keep paths involving top-level IOs
      p.sink.module == dut.name && p.sources.exists(_.module == dut.name)
    }
    val filterPathsByName = filteredPaths.map { p => // map ComponentNames in paths into string forms
      val mappedSources = p.sources.filter(_.module == dut.name).map { component =>
        componentToName(component)
      }
      componentToName(p.sink) -> mappedSources
    }
    val mapPairs = filterPathsByName.map { case (sink, sources) => // convert to Data
      nameToData(sink) -> sources.map { source =>
        nameToData(source)
      }.toSet
    }
    mapPairs.toMap
  }
}
