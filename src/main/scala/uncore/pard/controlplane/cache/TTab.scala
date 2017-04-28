package uncore.pard

import chisel3._
import chisel3.util._

import config._


object OP {
  val LE = 0
  val GE = 1 + LE
}

/**
 * Trigger Table
 * @param cpid Control plane id
 */
class TTab(cpid: Int)(implicit p: Parameters) extends Module
  with HasPipeline
  with HasTable {
  val nEntries = p(NEntries)
  val io = IO(new TableBundle {
    val trigger_dsid       = Output(UInt(p(TagBits).W))
    val trigger_rdata      = Input(UInt(p(TriggerRDataBits).W))
    val trigger_metric     = Output(UInt(p(TriggerMetricBits).W))
    val trigger_dsid_valid = Input(Bool())
    val fifo               = new DecoupledIO(UInt(16.W))
  })

  val trigger_ok = Wire(Bool())
  val stall = trigger_ok && !io.fifo.ready
  val trigger_idx = RegInit(UInt(log2Ceil(nEntries).W), 0.U)
  val trigger_idx_piped = pipelined(trigger_idx, 1, !stall)
  val trigger_rdata_piped = pipelined(io.trigger_rdata, 1, !stall)
  val trigger_dsid_valid_piped = pipelined(io.trigger_dsid_valid, 1, !stall)

  // Field Declarations
  val trigger_ids = makeField(UInt(8.W))()
  val valids      = makeField(Bool(), io.fifo.valid && io.fifo.ready, SetLast) { valid =>
    valid(trigger_idx_piped) := false.B
  }
  val dsids       = makeField(UInt(p(TagBits).W))()
  val metrics     = makeField(UInt(p(TriggerMetricBits).W))()
  val vals        = makeField(UInt(p(TriggerRDataBits).W))()
  val ops         = makeField(UInt(2.W))()

  makeRead(io.table.data, io.cmd.row, io.cmd.col)

  when (!stall) {
    trigger_idx := trigger_idx + 1.U
  }
  val compare_result = MuxLookup(ops(trigger_idx_piped), false.B, List(
    OP.GE.U -> (trigger_rdata_piped > vals(trigger_idx_piped)),
    OP.LE.U -> (trigger_rdata_piped < vals(trigger_idx_piped))))
  trigger_ok := valids(trigger_idx_piped) && compare_result && trigger_dsid_valid_piped
  io.trigger_dsid := dsids(trigger_idx)
  io.trigger_metric := metrics(trigger_idx)
  io.fifo.valid := trigger_ok
  io.fifo.bits := Cat(cpid.U(8.W), trigger_ids(trigger_idx_piped))
}
