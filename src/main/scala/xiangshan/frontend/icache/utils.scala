package xiangshan.frontend.icache

import chisel3._
import difftest._


class DiffICacheDebugIO extends DifftestBundle {
  val write_en = Input(Bool())
  val write_master = Input(Bool())
  val ptag = Input(UInt(64.W))
  val pidx = Input(UInt(64.W))
  val waymask = Input(UInt(16.W))
  val time = Input(UInt(64.W))
}

class DifftestICacheDebug extends DifftestBaseModule(new DiffICacheDebugIO)
