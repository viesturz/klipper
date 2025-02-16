package machine

class NeedsRestartException(msg: String): RuntimeException(msg)
class ConfigurationException(msg: String): RuntimeException(msg)

class CommandException(cmd: String) : RuntimeException(cmd)
class InvalidGcodeException(cmd: String) : RuntimeException(cmd)
class MissingRequiredParameterException(cmd: String) : RuntimeException(cmd)
class InvalidParameterException(cmd: String) : RuntimeException(cmd)
class FailedToParseParamsException(cmd: String) : RuntimeException(cmd)

class MoveOutsideRangeException(msg: String) : RuntimeException(msg)
