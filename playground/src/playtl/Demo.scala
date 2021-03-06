package playtl

import chisel3._
import chisel3.stage.{ChiselGeneratorAnnotation, NoRunFirrtlCompilerAnnotation}
import chisel3.util._
import freechips.rocketchip._
import diplomacy._
import tilelink._
import config._
import treadle.WriteVcdAnnotation

class DemoTLClient()(implicit p: Parameters) extends LazyModule {
  val node = TLClientNode(Seq(
    TLClientPortParameters(Seq(
      TLClientParameters("demo-client")
    ))
  ))

  lazy val module = new LazyModuleImp(this) {
    val (out: TLBundle, edge) = node.out.head

    require(edge.manager.beatBytes == 16)

    /** address of */
    val addr = RegInit(0.U(13.W))
    val size = RegInit(0.U(4.W))
    val put = RegInit(false.B)
    val width = 12
    val count = RegInit(0.U(12.W))
    val limit = (~(-1.S(12.W).asUInt << size) (11, 0) >> 4).asUInt

    val data = Cat(Seq.tabulate(16) { i => i.U | ((count(3, 0) + 1.U) << 4).asUInt }.reverse)

    val (legalg, gbits) = edge.Get(0.U, addr, size)
    val (legalp, pbits) = edge.Put(0.U, addr, size, data)
    val legal = Mux(put, legalp, legalg)
    val bits = Mux(put, pbits, gbits)

    out.a.valid := legal
    out.a.bits := bits
    out.b.ready := true.B
    out.c.valid := false.B
    out.d.ready := true.B
    out.e.valid := false.B

    when(out.a.fire()) {
      count := count + 1.U
    }

    when(!legal || (out.a.fire() && Mux(put, count === limit, true.B))) {
      count := 0.U
      size := size + 1.U
      put := chisel3.util.random.LFSR(16)(0)
      addr := addr + 0x100.U
      when(size === 8.U) {
        size := 0.U
      }
    }
  }
}

class DemoTLManager()(implicit p: Parameters) extends LazyModule {
  val node = TLManagerNode(Seq(
    TLManagerPortParameters(Seq(
      TLManagerParameters(Seq(AddressSet(0, 0xfff)))
    ), 4)
  ))
  lazy val module = new LazyModuleImp(this) {

  }
}

class Demo()(implicit p: Parameters) extends LazyModule {
  val ram = LazyModule(new TLRAM(AddressSet(0, 0xfff)))
  val client = LazyModule(new DemoTLClient)

  ram.node :=
    TLFragmenter(4, 16) :=
    TLWidthWidget(16) :=
    client.node

  lazy val module = new LazyModuleImp(this)
}

object DemoEmitter extends App {
  implicit val p = new Config((site, here, up) => {
    case MonitorsEnabled => false
  })
  new chisel3.stage.ChiselStage run Seq(ChiselGeneratorAnnotation(() => LazyModule(new Demo).module), NoRunFirrtlCompilerAnnotation)
}

object DemoTester extends App {

  import chiseltest._

  implicit val p = new Config((site, here, up) => {
    case MonitorsEnabled => false
  })

  RawTester.test(LazyModule(new Demo).module, Seq(WriteVcdAnnotation)) { c =>
    c.clock.step(100)
    true
  }

}