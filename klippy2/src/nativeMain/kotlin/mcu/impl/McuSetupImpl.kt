package mcu.impl

import config.AnalogInPin
import config.DigitalInPin
import config.DigitalOutPin
import config.McuConfig
import config.StepperPins
import config.UartPins
import machine.impl.Reactor
import mcu.Mcu
import mcu.McuSetup
import mcu.PwmPin
import mcu.StepperMotor
import mcu.components.McuAnalogPin
import mcu.components.McuButton
import mcu.components.McuHwPwmPin
import mcu.components.McuPwmPin
import mcu.components.McuStepperMotor
import mcu.components.TmcUartBus
import mcu.connection.McuConnection

class McuSetupImpl(override val config: McuConfig, val connection: McuConnection): McuSetup {
    private val configuration = McuConfigureImpl(connection.commands)
    private val mcu = McuImpl(config, connection, configuration)

    init {
        add(McuBasics(mcu, configuration))
    }

    private fun add(component: McuComponent) = configuration.components.add(component)

    override suspend fun start(reactor: Reactor): Mcu {
        mcu.start(reactor)
        return mcu
    }

    override fun addButton(pin: DigitalInPin) = McuButton(mcu, pin, configuration).also { add(it) }
    override fun addPwmPin(config: DigitalOutPin): PwmPin =
        (if (config.hardwarePwm) McuHwPwmPin(mcu, config, configuration)
        else McuPwmPin(mcu, config, configuration)).also { add(it) }
    override fun addAnalogPin(pin: AnalogInPin) = McuAnalogPin(mcu, pin, configuration).also { add(it) }
    override fun addStepperMotor(config: StepperPins): StepperMotor = McuStepperMotor(mcu, config, configuration).also { add(it) }
    override fun addTmcUart(config: UartPins) = TmcUartBus(mcu, config, configuration).also { add(it) }
}