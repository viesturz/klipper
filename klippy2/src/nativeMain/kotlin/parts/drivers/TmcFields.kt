package parts.drivers


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
}

class TmcFields(val connection: TmcUartNodeWithAddress, registers: List<TmcRegister>) {
    private val registers = HashMap<UByte, UInt>()
    private val fields = HashMap<TmcField, TmcFieldAddr>()
    private val fieldToRegister = HashMap<TmcField, TmcRegister>()

    init {
        addRegisters(registers)
    }
    fun addRegisters(f: List<TmcRegister>) {
        f.forEach { reg -> reg.fields.forEach { field ->
            this.fields[field.field] = field
            this.fieldToRegister[field.field] = reg
        }}
    }

    suspend fun readField(field: TmcField): Int {
        val addr = fields.getValue(field)
        val regAddr = fieldToRegister.getValue(field)
        if (!regAddr.mode.canRead) {
            throw IllegalArgumentException("Field $field is not readable, mode: ${regAddr.mode.name}")
        }
        val register = connection.readRegister(regAddr.addr)
        registers[regAddr.addr] = register
        val data = (register shr addr.offset) and ((1u shl addr.numBits) - 1u)
        if (addr.signed && (data and (1u shl (addr.numBits - 1)) != 0u)) {
            return data.toInt() - (1 shl addr.numBits)
        }
        return data.toInt()
    }

    suspend fun writeField(field: TmcField, value: Boolean) {
        writeField(field, if (value) 1 else 0)
    }

    suspend fun writeField(field: TmcField, value: Int) {
        val addr = fields.getValue(field)
        val regAddr = fieldToRegister.getValue(field)
        if (!regAddr.mode.canWrite) {
            throw IllegalArgumentException("Field $field is not writable, mode: ${regAddr.mode.name}")
        }
        var register = 0u
        if (regAddr.mode.canRead) {
            if (!registers.containsKey(regAddr.addr)) {
                registers[regAddr.addr] = connection.readRegister(regAddr.addr)
            }
            register = registers.getValue(regAddr.addr)
        }

        val mask = ((1u shl addr.numBits)-1u)
        val clippedValue = value.toUInt() and mask
        val updatedRegister = register and (mask shl addr.offset).inv() or (clippedValue shl addr.offset)
        registers[regAddr.addr] = updatedRegister
        connection.writeRegister(regAddr.addr, updatedRegister)
    }

    suspend fun clearField(field: TmcField) {
        val addr = fields.getValue(field)
        val regAddr = fieldToRegister.getValue(field)
        if (regAddr.mode != TmcRegisterMode.RC) {
            throw IllegalArgumentException("Field $field is not clearable, mode: ${regAddr.mode.name}")
        }
        val clearMask = ((1u shl addr.numBits)-1u) shl addr.offset
        connection.writeRegister(regAddr.addr, clearMask)
        registers.remove(regAddr.addr)
    }
}