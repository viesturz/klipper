package parts.drivers

import MachineTime
import config.DigitalOutPin
import config.TmcAddressUartPins
import io.github.oshai.kotlinlogging.KotlinLogging
import MachineBuilder
import MachineRuntime
import PartLifecycle
import StepperDriver

fun MachineBuilder.TMC2209(
    name: String = defaultName("TMC2209_"),
    pins: TmcAddressUartPins,
    senseResistor: Double, // Typically 0.11 ohms, but check your board.
    runCurrent: Double,
    microsteps: Int = 1,
    interpolate: Boolean = true,
    enablePin: DigitalOutPin? = null,
    idleCurrent: Double = runCurrent,
    maxStealthchopSpeed: Int = 999999999,
): StepperDriver {
    val comms = setupMcu(pins.uartPins.mcu).addTmcUart(pins.uartPins)
    val fields = TmcFields(TmcUartNodeWithAddress(pins, comms), registers)
    val impl = TMC2209Impl(
        name = name,
        fields = fields,
        enable = TmcEnableTracking(fields, enablePin,this).also { addPart(it) },
        currentHelper = TmcCurrent(senseResistor, runCurrent, idleCurrent),
        _microsteps = TmcMicrosteps(microsteps, interpolate),
        maxStealthchopSpeed,
    ).also { addPart(it) }
    return impl
}

class TMC2209Impl(
    override val name: String,
    val fields: TmcFields,
    val enable: TmcEnableTracking,
    val currentHelper: TmcCurrent,
    val _microsteps: TmcMicrosteps,
    val stealthchopTreshold: Int,
) : PartLifecycle, StepperDriver {
    private val logger = KotlinLogging.logger("Tmc2209 $name")
    override val stepBothEdges = true
    override val pulseDuration = 0.000_000_100
    override val microsteps: Int
        get() = _microsteps.microsteps

    var _enabled = false
    override val enabled: Boolean
        get() = _enabled

    override suspend fun setEnabled(time: MachineTime, enabled: Boolean) {
        if (enabled == _enabled) return
        _enabled = enabled
        if (enabled) {
            logger.info { "Enabling" }
            enable.enable(time)
            fields.readAllRegs()
            logger.info { "Registers on enable: ${fields.debugPrint()}" }
        } else {
            enable.disable(time)
        }
    }

    override suspend fun onStart(runtime: MachineRuntime) {
        logger.info { "OnStart" }
        // Setup fields for UART
        fields.set(TmcField.pdn_disable, true)
        fields.set(TmcField.senddelay, 2)  // Avoid tx errors on shared uart
        fields.flush()
        // Setup basic fields
        fields.readAllRegs()
        fields.set(TmcField.iholddelay, 8)
        // GCONF
        fields.set(TmcField.mstep_reg_select, true)
        fields.set(TmcField.multistep_filt, true)
        fields.set(TmcField.toff, 3)
        fields.set(TmcField.hstrt, 5)
        fields.set(TmcField.hend, 0)
        fields.set(TmcField.tbl, 2)
        // Configure pwmconf
        fields.set(TmcField.pwm_ofs, 36)
        fields.set(TmcField.pwm_grad, 14)
        fields.set(TmcField.pwm_freq, 1)
        fields.set(TmcField.pwm_autoscale, true)
        fields.set(TmcField.pwm_autograd, true)
        fields.set(TmcField.pwm_reg, 8)
        fields.set(TmcField.pwm_lim, 12)
        // CHOPCONF
        fields.set(TmcField.dedge, true)

        enable.disable(0.0)
        _microsteps.set(fields)
        currentHelper.setCurrent(fields, currentHelper.runCurrent, currentHelper.idleCurrent)

        fields.flush()
        logger.info { "onStart done, fields: ${fields.debugPrint()}" }
    }

    override fun status(): Map<String, Any> =mapOf("enabled" to enabled)
}

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
    TmcRegister(0x10u, TmcRegisterMode.W, listOf(
        TmcFieldAddr(TmcField.ihold, 0, 5),
        TmcFieldAddr(TmcField.irun, 8, 5),
        TmcFieldAddr(TmcField.iholddelay, 16, 4),
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
    TmcRegister(0x70u, TmcRegisterMode.RW, listOf(
        TmcFieldAddr(TmcField.pwm_ofs, 0, 8),
        TmcFieldAddr(TmcField.pwm_grad, 8, 8),
        TmcFieldAddr(TmcField.pwm_freq, 16, 2),
        TmcFieldAddr(TmcField.pwm_autoscale, 16, 1),
        TmcFieldAddr(TmcField.pwm_autograd, 19, 1),
        TmcFieldAddr(TmcField.freewheel, 20, 2),
        TmcFieldAddr(TmcField.pwm_reg, 24, 4),
        TmcFieldAddr(TmcField.pwm_lim, 28, 4),
    )),
)
