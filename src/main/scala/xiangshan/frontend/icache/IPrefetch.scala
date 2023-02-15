/***************************************************************************************
  * Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
  * Copyright (c) 2020-2021 Peng Cheng Laboratory
  *
  * XiangShan is licensed under Mulan PSL v2.
  * You can use this software according to the terms and conditions of the Mulan PSL v2.
  * You may obtain a copy of Mulan PSL v2 at:
  *          http://license.coscl.org.cn/MulanPSL2
  *
  * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
  * EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
  * MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
  *
  * See the Mulan PSL v2 for more details.
  ***************************************************************************************/

package xiangshan.frontend.icache

import chipsalliance.rocketchip.config.Parameters
import chisel3._
import chisel3.util._
import freechips.rocketchip.tilelink._
import utils._
import xiangshan.cache.mmu._
import xiangshan.frontend._
import xiangshan.backend.fu.{PMPReqBundle, PMPRespBundle}
import huancun.{PreferCacheKey}


abstract class IPrefetchBundle(implicit p: Parameters) extends ICacheBundle
abstract class IPrefetchModule(implicit p: Parameters) extends ICacheModule

//TODO: remove this
object DebugFlags {
  val fdip = false
}

class PIQReq(implicit p: Parameters) extends IPrefetchBundle {
  val paddr      = UInt(PAddrBits.W)
}

class PIQData(implicit p: Parameters) extends IPrefetchBundle {
  val ptage = UInt(tagBits.W)
  val phySetIdx = UInt(phyIdxBits.W)
  val cacheline = UInt(blockBits.W)
  val writeBack = Bool()
}

class PIQToMainPipe(implicit  p: Parameters) extends IPrefetchBundle{
  val info = DecoupledIO(new PIQData)
}
/* need change name */
class MainPipeToPrefetchPipe(implicit p: Parameters) extends IPrefetchBundle {
  val ptage = UInt(tagBits.W)
  val phySetIdx = UInt(phyIdxBits.W)
}

class MainPipeMissInfo(implicit p: Parameters) extends IPrefetchBundle {
  val s1_already_check_ipf = Output(Bool())
  val s2_miss_info = Vec(PortNumber, ValidIO(new MainPipeToPrefetchPipe))
}

class IPrefetchToMissUnit(implicit  p: Parameters) extends IPrefetchBundle{
  val enqReq  = DecoupledIO(new PIQReq)
}

class IPredfetchIO(implicit p: Parameters) extends IPrefetchBundle {
  val fromFtq         = Flipped(new FtqPrefechBundle)
  val iTLBInter       = new TlbRequestIO
  val pmp             =   new ICachePMPBundle
  val toIMeta         = Decoupled(new ICacheReadBundle)
  val fromIMeta       = Input(Vec(aliasBankNum, new ICacheMetaRespBundle))
  val toMissUnit      = new IPrefetchToMissUnit
  val freePIQEntry    = Input(UInt(log2Ceil(nPrefetchEntries).W))
  val fromMSHR        = Flipped(Vec(PortNumber,ValidIO(UInt(PAddrBits.W))))
  val IPFBufferRead   = Flipped(new IPFBufferFilterRead)
  /** icache main pipe to prefetch pipe*/
  val fromMainPipe    = Flipped(Vec(PortNumber,ValidIO(new MainPipeToPrefetchPipe)))

  val prefetchEnable = Input(Bool())
  val prefetchDisable = Input(Bool())
  val fencei         = Input(Bool())
}

/** Prefetch Buffer **/


class PrefetchBuffer(implicit p: Parameters) extends IPrefetchModule
{
  val io = IO(new Bundle{
    val read  = new IPFBufferRead
    val filter_read = new IPFBufferFilterRead
    val write = Flipped(ValidIO(new IPFBufferWrite))
    /** to ICache replacer */
    val replace = new IPFBufferMove
    /** move & move filter port */
    val mainpipe_missinfo = Flipped(new MainPipeMissInfo)
    val meta_filter_read = new ICacheMetaReqBundle
    val move  = new Bundle() {
      val meta_write = DecoupledIO(new ICacheMetaWrapperWriteBundle)
      val data_write = DecoupledIO(new ICacheDataWrapperWriteBundle)
    }
    val fencei = Input(Bool())
  })

  class IPFBufferEntryMeta(implicit p: Parameters) extends IPrefetchBundle
  {
    val tag = UInt(tagBits.W)
    val paddr = UInt(PAddrBits.W)
    val valid = Bool()
    val confidence = UInt(log2Ceil(maxIPFMoveConf + 1).W)
    val move = Bool()
  }

  class IPFBufferEntryData(implicit p: Parameters) extends IPrefetchBundle
  {
    val cachline = UInt(blockBits.W)
  }

  def InitQueue[T <: Data](entry: T, size: Int): Vec[T] ={
    return RegInit(VecInit(Seq.fill(size)(0.U.asTypeOf(entry.cloneType))))
  }

  val meta_buffer = InitQueue(new IPFBufferEntryMeta, size = nIPFBufferSize)
  val data_buffer = InitQueue(new IPFBufferEntryData, size = nIPFBufferSize)

  /** filter read logic */
  val fr_ptagAndIdx = getPhyTagAndPhyIdxFromPaddr(io.filter_read.req.paddr)
  val fr_hit_in_buffer = meta_buffer.map(e => e.valid && fr_ptagAndIdx === getPhyTagAndPhyIdxFromPaddr(e.paddr)).reduce(_||_)
  val fr_hit_in_s1, fr_hit_in_s2, fr_hit_in_s3 = Wire(Bool())

  io.filter_read.resp.ipf_hit := fr_hit_in_buffer || fr_hit_in_s1 || fr_hit_in_s2 || fr_hit_in_s3

  /** read logic */
  (0 until PortNumber).foreach(i => io.read.req(i).ready := true.B)
  val r_valid = VecInit((0 until PortNumber).map( i => io.read.req(i).valid)).reduce(_||_)
  val r_ptagAndIdx = VecInit((0 until PortNumber).map(i => getPhyTagAndPhyIdxFromPaddr(io.read.req(i).bits.paddr)))
  val r_hit_oh = VecInit((0 until PortNumber).map(i =>
  VecInit(meta_buffer.map(entry =>
    io.read.req(i).valid && // need this condition
    entry.valid &&
    getPhyTagAndPhyIdxFromPaddr(entry.paddr) === r_ptagAndIdx(i)
  ))))
  val r_buffer_hit = VecInit(r_hit_oh.map(_.reduce(_||_)))
  val r_buffer_hit_idx = VecInit(r_hit_oh.map(PriorityEncoder(_)))
  val r_buffer_hit_data = VecInit((0 until PortNumber).map(i => Mux1H(r_hit_oh(i), data_buffer.map(_.cachline))))

  /** "read" also check data in move pipeline */
  val r_moves1pipe_hit_s1, r_moves1pipe_hit_s2, r_moves1pipe_hit_s3 = WireInit(VecInit(Seq.fill(PortNumber)(false.B)))
  val s1_move_data_cacheline, s2_move_data_cacheline, s3_move_data_cacheline = Wire(UInt(blockBits.W))

  (0 until PortNumber).foreach{ i =>
    io.read.resp(i).valid := io.read.req(i).valid
    io.read.resp(i).bits.ipf_hit := r_buffer_hit(i) || r_moves1pipe_hit_s1(i) || r_moves1pipe_hit_s2(i) || r_moves1pipe_hit_s3(i)
    io.read.resp(i).bits.cacheline := Mux(r_buffer_hit(i), r_buffer_hit_data(i),
      Mux(r_moves1pipe_hit_s1(i), s1_move_data_cacheline,
        Mux(r_moves1pipe_hit_s2(i), s2_move_data_cacheline, s3_move_data_cacheline)))
  }

  /** move logic */
  val r_buffer_hit_s2     = RegNext(r_buffer_hit, init=0.U.asTypeOf(r_buffer_hit.cloneType))
  val r_buffer_hit_idx_s2 = RegNext(r_buffer_hit_idx)
  val r_rvalid_s2         = RegNext(r_valid, init=false.B)

  val s2_move_valid_0 = r_rvalid_s2 && r_buffer_hit_s2(0)
  val s2_move_valid_1 = r_rvalid_s2 && r_buffer_hit_s2(1)

  XSPerfAccumulate("prefetch_hit_bank_0", r_rvalid_s2 && r_buffer_hit_s2(0))
  XSPerfAccumulate("prefetch_hit_bank_1", r_rvalid_s2 && r_buffer_hit_s2(1))

  val move_queue    = RegInit(VecInit(Seq.fill(nIPFBufferSize)(0.U.asTypeOf(r_buffer_hit_idx_s2(0)))))

  val curr_move_ptr = RegInit(0.U(log2Ceil(nIPFBufferSize).W))
  val curr_hit_ptr  = RegInit(0.U(log2Ceil(nIPFBufferSize).W))

  val s2_move_conf_full_0 = meta_buffer(r_buffer_hit_idx_s2(0)).confidence === (maxIPFMoveConf).U
  val s2_move_conf_full_1 = meta_buffer(r_buffer_hit_idx_s2(1)).confidence === (maxIPFMoveConf).U

  val move_repeat_0 = meta_buffer(r_buffer_hit_idx_s2(0)).move
  val move_repeat_1 = meta_buffer(r_buffer_hit_idx_s2(1)).move || (r_buffer_hit_idx_s2(0) === r_buffer_hit_idx_s2(1))

  val s2_move_0 = s2_move_valid_0 && !move_repeat_0
  val s2_move_1 = s2_move_valid_1 && !move_repeat_1

  val s2_move_enqueue_0 = s2_move_0 && s2_move_conf_full_0
  val s2_move_enqueue_1 = s2_move_1 && s2_move_conf_full_1

  when(s2_move_0) {
    when(s2_move_conf_full_0) {
      meta_buffer(r_buffer_hit_idx_s2(0)).move := true.B
    }.otherwise {
      meta_buffer(r_buffer_hit_idx_s2(0)).confidence := meta_buffer(r_buffer_hit_idx_s2(0)).confidence + 1.U
    }
  }
  when(s2_move_1) {
    when(s2_move_conf_full_1) {
      meta_buffer(r_buffer_hit_idx_s2(1)).move := true.B
    }.otherwise {
      meta_buffer(r_buffer_hit_idx_s2(1)).confidence := meta_buffer(r_buffer_hit_idx_s2(1)).confidence + 1.U
    }
  }

  when(s2_move_enqueue_0 && !s2_move_enqueue_1) {
    move_queue(curr_hit_ptr) := r_buffer_hit_idx_s2(0)
    
    when((curr_hit_ptr + 1.U) =/= curr_move_ptr){
      curr_hit_ptr := curr_hit_ptr + 1.U
    }
  }.elsewhen(!s2_move_enqueue_0 && s2_move_enqueue_1) {
    move_queue(curr_hit_ptr) := r_buffer_hit_idx_s2(1)
    
    when((curr_hit_ptr + 1.U) =/= curr_move_ptr){
      curr_hit_ptr := curr_hit_ptr + 1.U
    }
  }.elsewhen(s2_move_enqueue_0 && s2_move_enqueue_1) {
    move_queue(curr_hit_ptr) := r_buffer_hit_idx_s2(0)
    move_queue(curr_hit_ptr + 1.U) := r_buffer_hit_idx_s2(1)
    when((curr_hit_ptr + 2.U) =/= curr_move_ptr){
      curr_hit_ptr := curr_hit_ptr + 2.U
    }.otherwise{
      curr_hit_ptr := curr_hit_ptr + 1.U
    }
  }

  val move_queue_empty = curr_move_ptr === curr_hit_ptr
  /** pipeline control signal */
  val s1_ready, s2_ready, s3_ready = Wire(Bool())
  val s0_fire, s1_fire, s2_fire, s3_fire = Wire(Bool())

  /** stage 0 */
  val s0_valid        = !move_queue_empty && meta_buffer(move_queue(curr_move_ptr)).move

  val s0_move_idx     = move_queue(curr_move_ptr)
  val s0_move_meta    = meta_buffer(s0_move_idx)
  val s0_move_data    = data_buffer(s0_move_idx)
  io.replace.phySetIdx  := getPhyIdxFromPaddr(meta_buffer(s0_move_idx).paddr)
  val s0_waymask      = io.replace.waymask

  s0_fire             := s0_valid && s1_ready

  /** curr_move_ptr control logic */
  val s0_move_jump = !move_queue_empty && !meta_buffer(move_queue(curr_move_ptr)).move
  when (s0_fire) {
    curr_move_ptr := curr_move_ptr + 1.U
    meta_buffer(s0_move_idx).valid := false.B
    meta_buffer(s0_move_idx).move  := false.B
    meta_buffer(s0_move_idx).confidence := 0.U
  }.elsewhen(s0_move_jump) {
    curr_move_ptr := curr_move_ptr + 1.U
  }

  /** stage 1 : send req to metaArray */
  val s1_valid        = generatePipeControl(lastFire = s0_fire, thisFire = s1_fire, thisFlush = io.fencei, lastFlush = false.B)

  val s1_move_idx     = RegEnable(s0_move_idx, s0_fire)
  val s1_move_meta    = RegEnable(s0_move_meta, s0_fire)
  val s1_move_data    = RegEnable(s0_move_data, s0_fire)
  val s1_waymask      = RegEnable(s0_waymask, s0_fire)

  io.meta_filter_read.toIMeta.valid             := s1_valid
  io.meta_filter_read.toIMeta.bits.isDoubleLine := false.B
  io.meta_filter_read.toIMeta.bits.vSetIdx(0)   := getIdxFromPaddr(s1_move_meta.paddr) // just use port 0
  io.meta_filter_read.toIMeta.bits.vSetIdx(1)   := DontCare

  s1_ready            := !s1_valid || s1_fire
  s1_fire             := s1_valid && io.meta_filter_read.toIMeta.ready && s2_ready

  fr_hit_in_s1 := s1_valid && getPhyTagAndPhyIdxFromPaddr(s1_move_meta.paddr) === fr_ptagAndIdx
  r_moves1pipe_hit_s1 := VecInit((0 until PortNumber).map(i => s1_valid && r_ptagAndIdx(i) === getPhyTagAndPhyIdxFromPaddr(s1_move_meta.paddr)))
  s1_move_data_cacheline := s1_move_data.cachline

  /** stage 2 : collect message from metaArray and mainPipe to filter */
  val s2_valid        = generatePipeControl(lastFire = s1_fire, thisFire = s2_fire, thisFlush = io.fencei, lastFlush = false.B)

  val s2_move_idx     = RegEnable(s1_move_idx, s1_fire)
  val s2_move_meta    = RegEnable(s1_move_meta, s1_fire)
  val s2_move_data    = RegEnable(s1_move_data, s1_fire)
  val s2_waymask      = RegEnable(s1_waymask, s1_fire)
  val s2_aliasBankIdx_oh = UIntToOH(getAliasBankIdxFromPhyAddr(s2_move_meta.paddr))
  val s2_all_meta_data = ResultHoldBypass(data = io.meta_filter_read.fromIMeta, valid = RegNext(s1_fire))
  val s2_meta_ptags = Mux1H(s2_aliasBankIdx_oh, s2_all_meta_data.map(bank => bank.tags))
  val s2_meta_valids = Mux1H(s2_aliasBankIdx_oh, s2_all_meta_data.map(bank => bank.entryValid))

  val s2_tag_eq_vec = VecInit((0 until nWays).map(w => s2_meta_ptags(0)(w) === s2_move_meta.tag)) // just use port 0
  val s2_tag_match_vec = VecInit(s2_tag_eq_vec.zipWithIndex.map{ case(way_tag_eq, w) => way_tag_eq && s2_meta_valids(0)(w)})
  val s2_hit_in_meta_array = ParallelOR(s2_tag_match_vec)

  val main_s2_missinfo = io.mainpipe_missinfo.s2_miss_info
  val s2_hit_main_s2_missreq = VecInit((0 until PortNumber).map(i =>
    main_s2_missinfo(i).valid && getPhyIdxFromPaddr(s2_move_meta.paddr) === main_s2_missinfo(i).bits.phySetIdx
      && s2_move_meta.tag === main_s2_missinfo(i).bits.ptage)).reduce(_||_)

  val s2_discard        = s2_hit_in_meta_array || s2_hit_main_s2_missreq // || s2_hit_main_s1_missreq
  val s2_discard_latch  = holdReleaseLatch(valid = s2_discard, release = s2_fire, flush = io.fencei)
  if(DebugFlags.fdip){
    when (s2_fire && s2_discard_latch) {
      printf("<%d> IPrefetchBuffer: s2_discard : hit_in_meta_array=%d,hit_in_main_s2=%d, ptag=0x%x, phyidx=0x%x\n",
        GTimer(), s2_hit_in_meta_array, s2_hit_main_s2_missreq, s2_move_meta.tag, getPhyIdxFromPaddr(s2_move_meta.paddr))
    }
  }

  s2_ready := !s2_valid || s2_fire
  s2_fire := s2_valid && s3_ready && io.mainpipe_missinfo.s1_already_check_ipf

  fr_hit_in_s2 := s2_valid && getPhyTagAndPhyIdxFromPaddr(s2_move_meta.paddr) === fr_ptagAndIdx
  r_moves1pipe_hit_s2 := VecInit((0 until PortNumber).map(i => s2_valid && r_ptagAndIdx(i) === getPhyTagAndPhyIdxFromPaddr(s2_move_meta.paddr)))
  s2_move_data_cacheline := s2_move_data.cachline

  /** stage 3 : move data to metaArray and dataArray */
  val s3_valid = generatePipeControl(lastFire = s2_fire, thisFire = s3_fire, thisFlush = io.fencei, lastFlush = false.B)

  val s3_move_idx = RegEnable(s2_move_idx, s2_fire)
  val s3_move_meta = RegEnable(s2_move_meta, s2_fire)
  val s3_move_data = RegEnable(s2_move_data, s2_fire)
  val s3_waymask = RegEnable(s2_waymask, s2_fire)
  val s3_discard = RegEnable(s2_discard_latch, s2_fire)

  io.move.meta_write.valid := s3_valid && !s3_discard && !io.fencei
  io.move.data_write.valid := s3_valid && !s3_discard && !io.fencei
  io.move.meta_write.bits.generate(
    tag = s3_move_meta.tag,
    coh = ClientMetadata(ClientStates.Branch),
    phyIdx = getPhyIdxFromPaddr(s3_move_meta.paddr),
    waymask = s3_waymask,
    bankIdx = getPhyIdxFromPaddr(s3_move_meta.paddr)(0))
  io.move.data_write.bits.generate(
    data = s3_move_data.cachline,
    phyIdx = getPhyIdxFromPaddr(s3_move_meta.paddr),
    waymask = s3_waymask,
    bankIdx = getPhyIdxFromPaddr(s3_move_meta.paddr)(0),
    paddr = s3_move_meta.paddr)

  s3_ready := !s3_valid || s3_fire
  s3_fire := io.move.meta_write.fire && io.move.data_write.fire || s3_discard || io.fencei
  assert((io.move.meta_write.fire && io.move.data_write.fire) || (!io.move.meta_write.fire && !io.move.data_write.fire),
  "meta and data array need fire at same time")

  fr_hit_in_s3 := s3_valid && getPhyTagAndPhyIdxFromPaddr(s3_move_meta.paddr) === fr_ptagAndIdx
  r_moves1pipe_hit_s3 := VecInit((0 until PortNumber).map(i => s3_valid && r_ptagAndIdx(i) === getPhyTagAndPhyIdxFromPaddr(s3_move_meta.paddr)))

  s3_move_data_cacheline := s3_move_data.cachline

  if (DebugFlags.fdip) {
    when(io.move.meta_write.fire) {
      printf("<%d> IPrefetchBuffer: move data to meta sram:ptag=0x%x,pidx=0x%x,waymask=0x%x\n",
        GTimer(), s3_move_meta.tag,getPhyIdxFromPaddr(s3_move_meta.paddr) ,s3_waymask )
    }
  }

  /** write logic */
  val replacer = ReplacementPolicy.fromString(Some("random"), nIPFBufferSize)
  val curr_write_ptr = RegInit(0.U(log2Ceil(nIPFBufferSize).W))
  val victim_way = curr_write_ptr + 1.U//replacer.way

  when(io.write.valid) {
    meta_buffer(curr_write_ptr).tag := io.write.bits.meta.tag
    meta_buffer(curr_write_ptr).paddr := io.write.bits.meta.paddr
    meta_buffer(curr_write_ptr).valid := true.B
    meta_buffer(curr_write_ptr).move  := false.B
    meta_buffer(curr_write_ptr).confidence := 0.U

    data_buffer(curr_write_ptr).cachline := io.write.bits.data

    //update replacer
    replacer.access(curr_write_ptr)
    curr_write_ptr := victim_way

    if(DebugFlags.fdip){
//      printf("(%d) IPrefetchBuffer: write into buffer, curr_write_ptr: %d, addr: 0x%x\n",GTimer(), curr_write_ptr,io.write.bits.meta.paddr)
    }
  }

  /** fencei: invalid all entries */
  when(io.fencei) {
    meta_buffer.foreach{
      case b =>
        b.valid := false.B
        b.move := false.B
        b.confidence := 0.U
    }
  }
  if(DebugFlags.fdip){
//    when(io.fencei){
//      printf(" %d :fencei\n",GTimer())
//    }
  }

}

class IPrefetchPipe(implicit p: Parameters) extends  IPrefetchModule
{
  val io = IO(new IPredfetchIO)

  val enableBit = RegInit(false.B)
  val maxPrefetchCoutner = RegInit(0.U(log2Ceil(nPrefetchEntries + 1).W))

  val reachMaxSize = maxPrefetchCoutner === nPrefetchEntries.U

  // when(io.prefetchEnable){
  //   enableBit := true.B
  // }.elsewhen((enableBit && io.prefetchDisable) || (enableBit && reachMaxSize)){
  //   enableBit := false.B
  // }
  // ignore prefetchEnable from ICacheMainPipe
  enableBit := true.B

  class PrefetchDir(implicit  p: Parameters) extends IPrefetchBundle
  {
    val valid = Bool()
    val paddr = UInt(PAddrBits.W)
  }

  val prefetch_dir = RegInit(VecInit(Seq.fill(nPrefetchEntries)(0.U.asTypeOf(new PrefetchDir))))

  val fromFtq = io.fromFtq
  val fromMainPipe = io.fromMainPipe
  val (toITLB,  fromITLB) = (io.iTLBInter.req, io.iTLBInter.resp)
  io.iTLBInter.req_kill := false.B
  val (toIMeta, fromIMeta) = (io.toIMeta, io.fromIMeta)
  val (toIPFBuffer, fromIPFBuffer) = (io.IPFBufferRead.req, io.IPFBufferRead.resp)
  val (toPMP,  fromPMP)   = (io.pmp.req, io.pmp.resp)
  val toMissUnit = io.toMissUnit

  val p0_fire, p1_fire, p2_fire, p3_fire =  WireInit(false.B)
  val p0_discard, p1_discard, p2_discard, p3_discard = WireInit(false.B)
  val p0_ready, p1_ready, p2_ready, p3_ready = WireInit(false.B)

  /** Prefetch Stage 0: req from Ftq */
  val p0_valid  =   fromFtq.req.valid
  val p0_vaddr  =   addrAlign(fromFtq.req.bits.target, blockBytes, VAddrBits)
  val p0_vaddr_reg = RegEnable(p0_vaddr, fromFtq.req.fire())

  /* Cancel request when prefetch not enable 
   * or the request from FTQ is same as last time */
  val p0_req_cancel = !enableBit || (p0_vaddr === p0_vaddr_reg) || io.fencei
  p0_fire   :=   p0_valid && p1_ready && toITLB.fire() && toIMeta.ready && enableBit && !p0_req_cancel
  p0_discard := p0_valid && p0_req_cancel

  toIMeta.valid     := p0_valid && !p0_discard
  toIMeta.bits.vSetIdx(0) := get_idx(p0_vaddr)

  toIMeta.bits.vSetIdx(1) := DontCare
  toIMeta.bits.isDoubleLine := false.B

  toITLB.valid         := p0_valid && !p0_discard
  toITLB.bits.size     := 3.U // TODO: fix the size
  toITLB.bits.vaddr    := p0_vaddr
  toITLB.bits.debug.pc := p0_vaddr

  toITLB.bits.kill                := DontCare
  toITLB.bits.cmd                 := TlbCmd.exec
  toITLB.bits.debug.robIdx        := DontCare
  toITLB.bits.debug.isFirstIssue  := DontCare


  fromITLB.ready := true.B

  fromFtq.req.ready :=  !p0_valid || p0_fire || p0_discard

  /** Prefetch Stage 1: cache probe filter */
  val p1_valid =  generatePipeControl(lastFire = p0_fire, thisFire = p1_fire || p1_discard, thisFlush = false.B, lastFlush = p0_discard)

  val p1_vaddr   =  RegEnable(p0_vaddr,    p0_fire)

  //tlb resp
  val tlb_resp_valid = RegInit(false.B)
  when(p0_fire) {tlb_resp_valid := true.B}
  .elsewhen(tlb_resp_valid && (p1_fire || p1_discard)) {tlb_resp_valid := false.B}

  val tlb_resp_paddr = ResultHoldBypass(valid = RegNext(p0_fire), data = fromITLB.bits.paddr(0))
  val tlb_miss       = ResultHoldBypass(valid = RegNext(p0_fire), data = fromITLB.bits.miss)
  val tlb_resp_pf    = ResultHoldBypass(valid = RegNext(p0_fire), data = fromITLB.bits.excp(0).pf.instr && tlb_resp_valid)
  val tlb_resp_af    = ResultHoldBypass(valid = RegNext(p0_fire), data = fromITLB.bits.excp(0).af.instr && tlb_resp_valid)

  val p1_exception  = VecInit(Seq(tlb_resp_pf, tlb_resp_af))
  val p1_has_except =  p1_exception.reduce(_ || _)
  val p1_paddr = tlb_resp_paddr

  val p1_ptag = get_phy_tag(p1_paddr)
  val p1_aliasBankIdx_oh = UIntToOH(getAliasBankIdxFromPhyAddr(p1_paddr))
  val p1_all_meta_data = ResultHoldBypass(data = fromIMeta, valid = RegNext(p0_fire))
  val p1_meta_data = Mux1H(p1_aliasBankIdx_oh, VecInit(p1_all_meta_data.map(bank => bank.metaData(0))))

  val p1_meta_ptags = p1_meta_data.map(way => way.tag)
  val p1_meta_cohs = p1_meta_data.map(way => way.coh)
  val p1_meta_valids = Mux1H(p1_aliasBankIdx_oh, VecInit(p1_all_meta_data.map(bank => bank.entryValid(0))))

  val p1_tag_eq_vec       =  VecInit(p1_meta_ptags.map(_  ===  p1_ptag ))
  val p1_tag_match_vec    =  VecInit(p1_tag_eq_vec.zipWithIndex.map{ case(way_tag_eq, w) => way_tag_eq && p1_meta_valids(w)})
  val p1_tag_match        =  ParallelOR(p1_tag_match_vec)

  val p1_check_in_mshr = VecInit(io.fromMSHR.map(mshr => mshr.valid && mshr.bits === addrAlign(p1_paddr, blockBytes, PAddrBits))).reduce(_||_)

  val (p1_hit, p1_miss)   =  (p1_valid && (p1_tag_match || p1_check_in_mshr) && !p1_has_except , p1_valid && !p1_tag_match && !p1_has_except && !p1_check_in_mshr)


  //overriding the invalid req
  val p1_req_cancle = (p1_hit || (tlb_resp_valid && p1_exception.reduce(_ || _)) || (tlb_resp_valid && tlb_miss) || io.fencei) && p1_valid
  val p1_req_accept   = p1_valid && tlb_resp_valid && p1_miss && !tlb_miss

  p1_ready    :=   p1_fire || p1_req_cancle || !p1_valid
  p1_fire     :=   p1_valid && p1_req_accept && p2_ready && enableBit
  p1_discard  :=   p1_valid && p1_req_cancle

  /** Prefetch Stage 2: filtered req PIQ enqueue */
  val p2_valid =  generatePipeControl(lastFire = p1_fire, thisFire = p2_fire || p2_discard, thisFlush = false.B, lastFlush = p1_discard)
  val p2_pmp_fire = p2_valid
  val pmpExcpAF = fromPMP.instr

  val p2_paddr     = RegEnable(p1_paddr,  p1_fire)
  val p2_except_pf = RegEnable(tlb_resp_pf, p1_fire)
  val p2_except_af = DataHoldBypass(pmpExcpAF, p2_pmp_fire) || RegEnable(tlb_resp_af, p1_fire)
  val p2_mmio      = DataHoldBypass(io.pmp.resp.mmio && !p2_except_af && !p2_except_pf, p2_pmp_fire)
  val p2_vaddr   =  RegEnable(p1_vaddr,    p1_fire)


  /*when a prefetch req meet with a miss req in MSHR cancle the prefetch req */
  val p2_check_in_mshr = VecInit(io.fromMSHR.map(mshr => mshr.valid && mshr.bits === addrAlign(p2_paddr, blockBytes, PAddrBits))).reduce(_||_)

  //TODO wait PMP logic
  val p2_exception  = VecInit(Seq(pmpExcpAF, p2_mmio)).reduce(_||_)

  io.pmp.req.valid      := p2_pmp_fire
  io.pmp.req.bits.addr  := p2_paddr
  io.pmp.req.bits.size  := 3.U
  io.pmp.req.bits.cmd   := TlbCmd.exec

  p2_ready :=   p2_fire || p2_discard || !p2_valid
  p2_fire  :=   p2_valid && !p2_exception && p3_ready && p2_pmp_fire
  p2_discard := p2_valid && (p2_exception && p2_pmp_fire || io.fencei)

  /** Prefetch Stage 2: filtered req PIQ enqueue */
  val p3_valid =  generatePipeControl(lastFire = p2_fire, thisFire = p3_fire || p3_discard, thisFlush = false.B, lastFlush = p2_discard)

  val p3_paddr = RegEnable(p2_paddr,  p2_fire)
  val p3_check_in_mshr = RegEnable(p2_check_in_mshr,  p2_fire)
  val p3_vaddr   =  RegEnable(p2_vaddr,    p2_fire)
  val p3_phyIdx = getPhyIdxFromPaddr(p3_paddr)
  // check in prefetch buffer
  toIPFBuffer.paddr := p3_paddr
  val p3_buffer_hit = fromIPFBuffer.ipf_hit

  val p3_hit_dir = VecInit((0 until nPrefetchEntries).map(i => prefetch_dir(i).valid && prefetch_dir(i).paddr === p3_paddr )).reduce(_||_)
  //Cache miss handling by main pipe
  val p3_hit_mp_miss = VecInit((0 until PortNumber).map(i => fromMainPipe(i).valid && (fromMainPipe(i).bits.ptage === get_phy_tag(p3_paddr) &&
                                                            (fromMainPipe(i).bits.phySetIdx === p3_phyIdx)))).reduce(_||_)
  val p3_req_cancel = p3_hit_dir || p3_check_in_mshr || !enableBit || p3_hit_mp_miss || p3_buffer_hit || io.fencei
  p3_discard := p3_valid && p3_req_cancel

  toMissUnit.enqReq.valid := p3_valid && !p3_req_cancel
  toMissUnit.enqReq.bits.paddr := p3_paddr
//  toMissUnit.enqReq.bits.phySetIdx := p3_phyIdx

  when(io.fencei){
    maxPrefetchCoutner := 0.U

    prefetch_dir.foreach(_.valid := false.B)
  }.elsewhen(toMissUnit.enqReq.fire()){
    when(reachMaxSize){
      prefetch_dir(io.freePIQEntry).paddr := p3_paddr
    }.otherwise {
      maxPrefetchCoutner := maxPrefetchCoutner + 1.U

      prefetch_dir(maxPrefetchCoutner).valid := true.B
      prefetch_dir(maxPrefetchCoutner).paddr := p3_paddr
    }
  }

  p3_ready := toMissUnit.enqReq.ready || !enableBit || p3_discard
  p3_fire  := toMissUnit.enqReq.fire()

  /** <PERF> all cache prefetch num */
  XSPerfAccumulate("ipf_send_get_to_L2", p3_fire)

  if (DebugFlags.fdip) {
//    when(toMissUnit.enqReq.fire()){
//      printf("(%d) PIQ enqueue, vaddr: 0x%x, paddr: 0x%x\n",GTimer(), p3_vaddr, p3_paddr)
//    }
//    when(p0_discard) {
//      printf("[%d] discard in p0, aligned vaddr: 0x%x, vaddr: 0x%x\n", GTimer(), p0_vaddr, fromFtq.req.bits.target)
//    }
//    when(p1_discard) {
//      printf("[%d] discard in p1, aligned vaddr: 0x%x\n", GTimer(), p1_vaddr)
//    }
//    when(p2_discard) {
//      printf("[%d] discard in p2, aligned vaddr: 0x%x\n", GTimer(), p2_vaddr)
//    }
//    when(p3_discard) {
//      printf("[%d] discard in p3, aligned vaddr: 0x%x\n", GTimer(), p3_vaddr)
//    }

  }

}

class PIQEntry(edge: TLEdgeOut, id: Int)(implicit p: Parameters) extends IPrefetchModule
{
  val io = IO(new Bundle{
    val id          = Input(UInt((log2Ceil(nPrefetchEntries + PortNumber)).W))

    val req         = Flipped(DecoupledIO(new PIQReq))

    val mem_acquire = DecoupledIO(new TLBundleA(edge.bundle))
    val mem_grant   = Flipped(DecoupledIO(new TLBundleD(edge.bundle)))

    //write back to Prefetch Buffer
    val piq_write_ipbuffer = DecoupledIO(new IPFBufferWrite)

    //TODO: fencei flush instructions
    val fencei      = Input(Bool())

    val prefetch_entry_data = DecoupledIO(new PIQData)
//    //hit in prefetch buffer
//    val hitFree     = Input(Bool())
//    //free as a victim
//    val replaceFree = Input(Bool())
  })

  val s_idle :: s_memReadReq :: s_memReadResp :: s_write_back :: s_finish:: Nil = Enum(5)
  val state = RegInit(s_idle)

  //req register
  val req = Reg(new PIQReq)
  val req_phyIdx = getPhyIdxFromPaddr(req.paddr) //physical index
  val req_tag = get_phy_tag(req.paddr)           //physical tag

  if(DebugFlags.fdip){
//    when(io.mem_acquire.fire()) {
//      printf("(%d) PIQEntry: acquire_fire, source id: %d, paddr: 0x%x\n", GTimer(), id.U, req.paddr)
//    }
//    when(RegNext(state === s_memReadResp) && (state === s_write_back)){
//      printf("(%d) PIQEntry: grant_done, source id: %d, paddr: 0x%x\n", GTimer(), id.U,  req.paddr)
//    }
  }

  val (_, _, refill_done, refill_address_inc) = edge.addr_inc(io.mem_grant)

  //8 for 64 bits bus and 2 for 256 bits
  val readBeatCnt = Reg(UInt(log2Up(refillCycles).W))
  val respDataReg = Reg(Vec(refillCycles,UInt(beatBits.W)))

  //to main pipe s1
  io.prefetch_entry_data.valid := state =/= s_idle
  io.prefetch_entry_data.bits.phySetIdx := req_phyIdx
  io.prefetch_entry_data.bits.ptage := req_tag
  io.prefetch_entry_data.bits.cacheline := respDataReg.asUInt
  io.prefetch_entry_data.bits.writeBack := state === s_write_back

  //initial
  io.mem_acquire.bits := DontCare
  io.mem_grant.ready := true.B
  io.piq_write_ipbuffer.bits:= DontCare

  io.req.ready := state === s_idle
  io.mem_acquire.valid := state === s_memReadReq

  val needFlushReg = RegInit(false.B)
  when(state === s_idle || state === s_finish){
    needFlushReg := false.B
  }
  when((state === s_memReadReq || state === s_memReadResp || state === s_write_back) && io.fencei){
    needFlushReg := true.B
  }
  val needFlush = needFlushReg || io.fencei
  //flush register
//  val needFlush = generateState(enable = io.fencei && (state =/= s_idle) && (state =/= s_finish), release = (state=== s_wait_free))
//
//  val freeEntry   = io.fencei
//  val needFree    = generateState(enable = freeEntry && (state === s_finish),  release = (state === s_finish))

  //state change
  switch(state){
    is(s_idle){
      when(io.req.fire()){
        readBeatCnt := 0.U
        state := s_memReadReq
        req := io.req.bits
      }
    }

    // memory request
    is(s_memReadReq){
      when(io.mem_acquire.fire()){
        state := s_memReadResp
      }
    }

    is(s_memReadResp){
      when (edge.hasData(io.mem_grant.bits)) {
        when (io.mem_grant.fire()) {
          readBeatCnt := readBeatCnt + 1.U
          respDataReg(readBeatCnt) := io.mem_grant.bits.data
          when (readBeatCnt === (refillCycles - 1).U) {
            assert(refill_done, "refill not done!")
            state := s_write_back
          }
        }
      }
    }

    is(s_write_back){
      state := Mux(io.piq_write_ipbuffer.fire() || needFlush, s_finish, s_write_back)
    }

    is(s_finish){
      state := s_idle
    }
  }

  //refill write and meta write
  //WARNING: Maybe could not finish refill in 1 cycle
  io.piq_write_ipbuffer.valid := (state === s_write_back) && !needFlush
  io.piq_write_ipbuffer.bits.meta.tag := req_tag
  io.piq_write_ipbuffer.bits.meta.paddr := req.paddr
  io.piq_write_ipbuffer.bits.data := respDataReg.asUInt
  io.piq_write_ipbuffer.bits.buffIdx := io.id - PortNumber.U

  XSPerfAccumulate("PrefetchEntryReq" + Integer.toString(id, 10), io.req.fire())

  //mem request
  io.mem_acquire.bits  := edge.Get(
    fromSource      = io.id,
    toAddress       = Cat(req.paddr(PAddrBits - 1, log2Ceil(blockBytes)), 0.U(log2Ceil(blockBytes).W)),
    lgSize          = (log2Up(cacheParams.blockBytes)).U)._2

}
