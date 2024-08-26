package parts.drivers

import config.TmcAddressUartPins
import machine.MachineBuilder
import machine.MachineRuntime
import machine.impl.PartLifecycle
import mcu.McuSetup

fun  MachineBuilder.TMC2209(
    name: String,
    pins: TmcAddressUartPins,
    microsteps: Int,
    senseResistor: Double,
    runCurrent: Double,
    idleCurrent: Double = runCurrent,
    stealthchopTreshold: Int = 999999999,
): StepperDriver = TMC2209Impl(name, pins, microsteps, senseResistor, runCurrent, idleCurrent, stealthchopTreshold, this).also { addPart(it) }

private val registers = listOf(
    TmcRegister(0x00u, TmcRegisterMode.RW, listOf(
        TmcFieldAddr(TmcField.i_scale_analog, 0, 1),
        TmcFieldAddr(TmcField.internal_rsense, 1, 1),
        TmcFieldAddr(TmcField.en_spreadcycle, 2, 1),
        TmcFieldAddr(TmcField.shaft, 3, 1),
        TmcFieldAddr(TmcField.index_otpw, 4, 1),
        TmcFieldAddr(TmcField.index_step, 5, 1),
        TmcFieldAddr(TmcField.pdn_disable, 6, 1),
        TmcFieldAddr(TmcField.mstep_reg_select, 7, 1),
        TmcFieldAddr(TmcField.multistep_filt, 8, 1),
        TmcFieldAddr(TmcField.test_mode, 9, 1),
    )),
    TmcRegister(0x01u, TmcRegisterMode.RC, listOf(
        TmcFieldAddr(TmcField.reset, 0, 1),
        TmcFieldAddr(TmcField.drv_err, 1, 1),
        TmcFieldAddr(TmcField.uv_cp, 2, 1),
    )),
    TmcRegister(0x02u, TmcRegisterMode.R, listOf(
        TmcFieldAddr(TmcField.ifcnt, 0, 8),
    )),
    TmcRegister(0x03u, TmcRegisterMode.W, listOf(
        TmcFieldAddr(TmcField.senddelay, 8, 4),
    )),
    TmcRegister(0x6Cu, TmcRegisterMode.RW, listOf(
        TmcFieldAddr(TmcField.toff, 0, 4),
        TmcFieldAddr(TmcField.hstrt, 4, 3),
        TmcFieldAddr(TmcField.hend, 7, 4),
        TmcFieldAddr(TmcField.tbl, 15, 2),
        TmcFieldAddr(TmcField.vsense, 17, 1),
        TmcFieldAddr(TmcField.mres, 24, 4),
        TmcFieldAddr(TmcField.intpol, 28, 1),
        TmcFieldAddr(TmcField.dedge, 29, 1),
        TmcFieldAddr(TmcField.diss2g, 30, 1),
        TmcFieldAddr(TmcField.diss2vs, 31, 1),
    )),
)

class TMC2209Impl(
    override val name: String,
    pinsConfig: TmcAddressUartPins,
    override val microsteps: Int,
    val senseResistor: Double,
    override val runCurrent: Double,
    override val idleCurrent: Double,
    val stealthchopTreshold: Int,
    setup: MachineBuilder
) : PartLifecycle, StepperDriver {
    val comms = setup.setupMcu(pinsConfig.uartPins.mcu).addTmcUart(pinsConfig.uartPins)
    val fields = TmcFields(TmcUartNodeWithAddress(pinsConfig, comms, setup), registers)

    override suspend fun onStart(runtime: MachineRuntime) {
        // Setup fields for UART
        fields.writeField(TmcField.pdn_disable, true)
        fields.writeField(TmcField.senddelay, 2)  // Avoid tx errors on shared uart
//        // Register commands
//        current_helper = tmc2130.TMC2130CurrentHelper(config, self.mcu_tmc)
//        cmdhelper = tmc.TMCCommandHelper(config, self.mcu_tmc, current_helper)
//        cmdhelper.setup_register_dump(ReadRegisters)
//        self.get_phase_offset = cmdhelper.get_phase_offset
//        // Setup basic register values
//        fields.writeField(TmcField.mstep_reg_select", true)
//        tmc.TMCStealthchopHelper(config, self.mcu_tmc, TMC_FREQUENCY)
    }

    override fun status(): Map<String, Any> =mapOf()
}
