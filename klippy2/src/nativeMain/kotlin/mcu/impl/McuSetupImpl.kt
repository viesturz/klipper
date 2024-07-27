package mcu.impl

import config.AnalogInPin
import config.DigitalInPin
import config.DigitalOutPin
import config.I2CPins
import config.McuConfig
import config.SpiPins
import config.StepperPins
import config.UartPins
import machine.impl.Reactor
import mcu.Endstop
import mcu.I2CBus
import mcu.Mcu
import mcu.McuSetup
import mcu.Neopixel
import mcu.PulseCounter
import mcu.PwmPin
import mcu.SPIBus
import mcu.StepperMotor
import mcu.UartBus
import mcu.components.McuAnalogPin
import mcu.components.McuButton
import mcu.components.McuHwPwmPin
import mcu.components.McuPwmPin
import mcu.components.McuStepperMotor
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

    override fun addPulseCounter(pin: DigitalInPin): PulseCounter {
        TODO("Not yet implemented")
    }

    override fun addDigitalOutPin(config: DigitalOutPin): mcu.DigitalOutPin {
        TODO("Not yet implemented")
    }

    override fun addI2C(config: I2CPins): I2CBus {
        TODO("Not yet implemented")
    }

    override fun addSpi(config: SpiPins): SPIBus {
        TODO("Not yet implemented")
    }

    override fun addNeopixel(config: DigitalOutPin): Neopixel {
        TODO("Not yet implemented")
    }

    override fun addEndstop(pin: DigitalInPin, motors: List<StepperMotor>): Endstop {
        TODO("Not yet implemented")
    }
    override fun addUart(config: UartPins): UartBus {
        TODO("Not yet implemented")
    }

}