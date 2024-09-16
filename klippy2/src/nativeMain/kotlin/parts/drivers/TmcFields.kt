package parts.drivers

import MachineTime


data class TmcFieldAddr(val field: TmcField, val offset: Int, val numBits: Int, val signed: Boolean = false)
data class TmcRegister(
    val addr: UByte,
    val mode: TmcRegisterMode,
    val fields: List<TmcFieldAddr>,
)
enum class TmcRegisterMode(val canRead: Boolean, val canWrite: Boolean) {
    R(true, false),
    RW(true, true),
    W(false, true),
    RC(true, false), // Read, clear
}

/** List of fields, depending on the model the driver will have a subset of fields. */
enum class TmcField {
    i_scale_analog,
    internal_rsense,
    en_spreadcycle,
    shaft,
    index_otpw,
    index_step,
    pdn_disable,
    mstep_reg_select,
    multistep_filt,
    test_mode,
    reset,
    drv_err,
    uv_cp,
    ifcnt,
    senddelay,
    toff,
    hstrt,
    hend,
    tbl,
    vsense,
    mres,
    intpol,
    dedge,
    diss2g,
    diss2vs,
    irun,
    ihold,
    iholddelay,
    pwm_ofs,
    pwm_grad,
    pwm_freq,
    pwm_autoscale,
    pwm_autograd,
    freewheel,
    pwm_reg,
    pwm_lim,
}

@OptIn(ExperimentalStdlibApi::class)
class TmcFields(val connection: TmcUartNodeWithAddress, registers: List<TmcRegister>) {
    private val registerValues = HashMap<UByte, UInt>()
    private val registers = HashMap<UByte, TmcRegister>()
    private val dirtyRegisters = HashMap<UByte, Boolean>()
    private val fields = HashMap<TmcField, TmcFieldAddr>()
    private val fieldToRegister = HashMap<TmcField, TmcRegister>()

    init {
        addRegisters(registers)
    }
    fun addRegisters(f: List<TmcRegister>) {
        f.forEach { reg ->
            this.registers[reg.addr] = reg
            reg.fields.forEach { field ->
                this.fields[field.field] = field
                this.fieldToRegister[field.field] = reg
        }}
    }

    fun hasField(f: TmcField) = fields.containsKey(f)

    suspend fun readField(field: TmcField): Int {
        val addr = fields.getValue(field)
        val regAddr = fieldToRegister.getValue(field)
        if (!regAddr.mode.canRead) {
            throw IllegalArgumentException("Field $field is not readable, mode: ${regAddr.mode.name}")
        }
        val register = connection.readRegister(regAddr.addr)
        registerValues[regAddr.addr] = register
        dirtyRegisters.remove(regAddr.addr)
        val data = (register shr addr.offset) and ((1u shl addr.numBits) - 1u)
        if (addr.signed && (data and (1u shl (addr.numBits - 1)) != 0u)) {
            return data.toInt() - (1 shl addr.numBits)
        }
        return data.toInt()
    }

    suspend fun set(field: TmcField, value: Boolean) {
        set(field, if (value) 1 else 0)
    }

    suspend fun set(field: TmcField, value: Int) {
        val addr = fields.getValue(field)
        val regAddr = fieldToRegister.getValue(field)
        if (!regAddr.mode.canWrite) {
            throw IllegalArgumentException("Field $field is not writable, mode: ${regAddr.mode.name}")
        }
        val register = when {
            registerValues.containsKey(regAddr.addr) -> registerValues.getValue(regAddr.addr)
            regAddr.mode.canRead -> connection.readRegister(regAddr.addr)
            else -> 0u
        }
        val mask = ((1u shl addr.numBits)-1u)
        val clippedValue = value.toUInt() and mask
        val updatedRegister = (register and (mask shl addr.offset).inv()) or (clippedValue shl addr.offset)
        registerValues[regAddr.addr] = updatedRegister
        dirtyRegisters[regAddr.addr] = true
    }

    suspend fun flush(time: MachineTime = 0.0) {
        if (dirtyRegisters.isEmpty()) return
        // Copy the changes, this will block and something else may write new values.
        val regs = ArrayList(dirtyRegisters.keys)
        val values = HashMap(registerValues)
        dirtyRegisters.clear()
        for (reg in regs) {
            val value = values.getValue(reg)
            connection.writeRegister(reg, value, time = time)
        }
    }

    suspend fun clear(field: TmcField) {
        val addr = fields.getValue(field)
        val regAddr = fieldToRegister.getValue(field)
        if (regAddr.mode != TmcRegisterMode.RC) {
            throw IllegalArgumentException("Field $field is not clearable, mode: ${regAddr.mode.name}")
        }
        val clearMask = ((1u shl addr.numBits)-1u) shl addr.offset
        connection.writeRegister(regAddr.addr, clearMask)
        registerValues.remove(regAddr.addr)
    }

    suspend fun readAllRegs() {
        for (reg in registers.values) {
            if (reg.mode.canRead) {
                registerValues[reg.addr] = connection.readRegister(reg.addr)
            }
        }
    }

    fun debugPrint() = buildString {
        for (f in fields) {
            val addr = f.value
            val register = fieldToRegister[f.key] ?: continue
            val reg = registerValues.get(register.addr) ?: continue
            var value = (reg shr addr.offset and ((1u shl addr.numBits) - 1u)).toInt()
            if (addr.signed && (value and (1 shl (addr.numBits - 1)) != 0)) {
                value = value - (1 shl addr.numBits)
            }
            append("${f.key.name} = $value; ")
        }
    }
}