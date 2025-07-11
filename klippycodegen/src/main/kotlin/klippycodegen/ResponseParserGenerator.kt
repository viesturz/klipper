package klippycodegen

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate
import java.io.OutputStream

class ResponseParserGenerator(
    private val options: Map<String, String>,
    private val logger: KSPLogger,
    private val codeGenerator: CodeGenerator,
) : SymbolProcessor {

    operator fun OutputStream.plusAssign(str: String) {
        this.write(str.toByteArray())
    }

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver
            // Getting all symbols that are annotated with @Function.
            .getSymbolsWithAnnotation("utils.RegisterMcuMessage")
            // Making sure we take only class declarations.
            .filterIsInstance<KSClassDeclaration>()

        // Exit from the processor in case nothing is annotated with @RegisterMcuMessage.
        if (!symbols.iterator().hasNext()) return emptyList()

        // The generated file will be located at:
        // build/generated/ksp/main/kotlin/klippycodegen/mcu/RegisterMcuMessage.kt
        val file = codeGenerator.createNewFile(
            // Make sure to associate the generated file with sources to keep/maintain it across incremental builds.
            // Learn more about incremental processing in KSP from the official docs:
            // https://kotlinlang.org/docs/ksp-incremental.html
            dependencies = Dependencies(false, *resolver.getAllFiles().toList().toTypedArray()),
            packageName = "mcu",
            fileName = "RegisterMcuMessage"
        )
        file += "package mcu\n"
        file += "val CLASS_TO_SERIALIZER = mapOf<String, mcu.MessageSerializer<*>>("
        symbols.forEach { it.accept(Visitor(file), Unit) }
        file += ")"
        file.close()
        val unableToProcess = symbols.filterNot { it.validate() }.toList()
        return unableToProcess
    }

    inner class Visitor(private val file: OutputStream) : KSVisitorVoid() {
        override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
            if (classDeclaration.classKind != ClassKind.CLASS) {
                logger.error("Only classes can be annotated with @RegisterMcuMessage", classDeclaration)
                return
            }

            val annotation = classDeclaration.annotations.firstOrNull { it.shortName.asString() == "RegisterMcuMessage" } ?: return
            val signature = annotation.arguments.first { arg -> arg.name?.asString() == "signature" }.value as String
            val properties = classDeclaration.getAllProperties().filter { it.validate() }
            val name = classDeclaration.qualifiedName?.asString() ?: throw RuntimeException("Anonymous classes not supported")

            /**
             *     "foobar" to object : MessageSerializer<MessageStepperGetPosition> {
             *         override val signature = "foo"
             *         override fun parse(p: ParserContext): MessageStepperGetPosition = MessageStepperGetPosition(p.parseId())
             *         override fun write(input: MessageStepperGetPosition, builder: CommandBuilder) {
             *             builder.addId(input.id)
             *         }
             *     },
             */
            file += " \"$name\" to object : mcu.MessageSerializer<$name>{\n"
            file += "   override val signature=\"${signature}\"\n"
            file += " override fun parse(p: ParserContext): $name = $name("
            properties.forEach { prop -> file += "${prop.simpleName.asString()} = p.${generateParser(prop)}," }
            file += ")\n"
            file += " override fun write(input: $name, builder: CommandBuilder) { "
            properties.forEach { prop ->
                generateWriter(prop)?.also { writeMethod ->
                    file += "builder.$writeMethod(input.${prop.simpleName.asString()});"
                }
            }
            file += "}\n"
            file += "},\n"
            // TODO: validate signature matches parameters.
        }

        private fun generateParser(prop: KSPropertyDeclaration): String {
            val type = prop.type.resolve()
            require(!type.isFunctionType)
            require(!type.isMarkedNullable)
            val typeName = type.declaration.qualifiedName?.asString()
            require(typeName != null)
            return when (typeName) {
                "mcu.ObjectId" -> "parseId()"
                "mcu.PinName" -> "parsePin()"
                "mcu.McuClock32" -> "parseClock()"
                "kotlin.UByte" -> "parseC()"
                "kotlin.Byte" -> "parseB()"
                "kotlin.ByteArray" -> "parseBytes()"
                "kotlin.UByteArray" -> "parseBytesU()"
                "kotlin.UShort" -> "parseHU()"
                "kotlin.Int" -> "parseI()"
                "kotlin.UInt" -> "parseU()"
                "kotlin.Long" -> "parseL()"
                "kotlin.String" -> "parseStr()"
                "kotlin.Boolean" -> "parseBoolean()"
                "MachineTime" -> when(prop.simpleName.asString()) {
                    "time" -> "receiveTime"
                    "receiveTime" -> "receiveTime"
                    "sendTime" -> "sendTime"
                    else -> throw RuntimeException("${prop.simpleName}: MachineTime fields need to be named time,receiveTime, or sentTime, was ${prop.simpleName.asString()}")
                }
                else -> throw RuntimeException("Unsupported Mcu response parameter type: $typeName")
            }
        }

        private fun generateWriter(prop: KSPropertyDeclaration): String? {
            val type = prop.type.resolve()
            require(!type.isFunctionType)
            require(!type.isMarkedNullable)
            val typeName = type.declaration.qualifiedName?.asString()
            require(typeName != null)
            return when (typeName) {
                "mcu.ObjectId" -> "addId"
                "mcu.PinName" -> "addPin"
                "mcu.McuClock32" -> "addU"
                "kotlin.UByte" -> "addC"
                "kotlin.Byte" -> "addC"
                "kotlin.ByteArray" -> "addBytes"
                "kotlin.UByteArray" -> "addBytes"
                "kotlin.UShort" -> "addHU"
                "kotlin.Int" -> "addI"
                "kotlin.UInt" -> "addU"
                "kotlin.Long" -> "addL"
                "kotlin.String" -> "addStr"
                "kotlin.Boolean" -> "addBoolean"
                "MachineTime" -> null
                else -> throw RuntimeException("Unsupported Mcu response parameter type: $typeName")
            }
        }
    }
}