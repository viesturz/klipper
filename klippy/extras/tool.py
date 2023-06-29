# Support for toolchnagers
#
# Copyright (C) 2023 Viesturs Zarins <viesturz@gmail.com>
#
# This file may be distributed under the terms of the GNU GPLv3 license.

from . import toolchanger

class Tool:

    def __init__(self, config):
        self.printer = config.get_printer()
        self.params = config.get_prefix_options('params_')
        self.gcode_macro = self.printer.load_object(config, 'gcode_macro')

        self.name = config.get_name()
        toolchanger_name = config.get('toolchanger', 'toolchanger')
        self.main_toolchanger = self.printer.load_object(config, 'toolchanger')
        self.toolchanger = self.printer.load_object(config, toolchanger_name)
        self.pickup_gcode = self.gcode_macro.load_template(
            config, 'pickup_gcode', self._config_get(config, 'pickup_gcode', ''))
        self.dropoff_gcode = self.gcode_macro.load_template(
            config, 'dropoff_gcode', self._config_get(config, 'dropoff_gcode', ''))
        self.gcode_x_offset = self._config_getfloat(
            config, 'gcode_x_offset', None)
        self.gcode_y_offset = self._config_getfloat(
            config, 'gcode_y_offset', None)
        self.gcode_z_offset = self._config_getfloat(
            config, 'gcode_z_offset', None)
        self.params = self.toolchanger.params | toolchanger.get_params_dict(config)
        self.extruder_name = self._config_get(config, 'extruder', None)
        self.extruder_stepper_name = self._config_get(config, 'extruder_stepper', None)
        self.extruder = None
        self.extruder_stepper = None
        self.fan_name = self._config_get(config, 'fan', None)
        self.fan = None
        self.t_command_restore_axis = self._config_get(
            config, 't_command_restore_axis', 'XYZ')
        self.tool_number = config.getint('tool_number', -1, minval=0)

        gcode = self.printer.lookup_object('gcode')
        gcode.register_mux_command("ASSIGN_TOOL", "TOOL", self.name,
                                   self.cmd_ASSIGN_TOOL,
                                   desc=self.cmd_ASSIGN_TOOL_help)
        if self.tool_number >= 0:
            self.assign_tool(self.tool_number)

        self.printer.register_event_handler("klippy:connect",
                                    self._handle_connect)

    def _handle_connect(self):
        self.extruder = self.printer.lookup_object(
            self.extruder_name) if self.extruder_name else None
        self.extruder_stepper = self.printer.lookup_object(
            self.extruder_stepper_name) if self.extruder_stepper_name else None
        self.fan = self.printer.lookup_object(
            self.fan_name) if self.fan_name else None

    def get_status(self, eventtime):
        return {'name': self.name,
                'toolchanger': self.toolchanger.name,
                'tool_number': self.tool_number,
                'extruder': self.extruder_name,
                'extruder_stepper': self.extruder_stepper_name,
                'fan': self.fan_name,
                'active': self.main_toolchanger.get_selected_tool() == self,
                'gcode_x_offset': self.gcode_x_offset if self.gcode_x_offset else 0.0,
                'gcode_y_offset': self.gcode_y_offset if self.gcode_y_offset else 0.0,
                'gcode_z_offset': self.gcode_z_offset if self.gcode_z_offset else 0.0,
                } | self.params

    def get_offset(self):
        return [
            self.gcode_x_offset if self.gcode_x_offset else 0.0,
            self.gcode_y_offset if self.gcode_y_offset else 0.0,
            self.gcode_z_offset if self.gcode_z_offset else 0.0,
        ]

    cmd_ASSIGN_TOOL_help = 'Assign tool to tool number'
    def cmd_ASSIGN_TOOL(self, gcmd):
        self.assign_tool(gcmd.getint('N', minval=0), replace = True)

    def assign_tool(self, number, replace = False):
        prev_number = self.tool_number
        self.tool_number = number
        self.main_toolchanger.assign_tool(self, number, prev_number, replace)
        self.register_t_gcode(number)

    def register_t_gcode(self, number):
        gcode = self.printer.lookup_object('gcode')
        name = 'T%d' % (number)
        desc = 'Select tool %d' % (number)
        existing = gcode.register_command(name, None)
        if existing:
            # Do not mess with existing
            gcode.register_command(name, existing, dec = desc)
        else:
            tc = self.main_toolchanger
            axis = self.t_command_restore_axis
            func = lambda gcmd: tc.select_tool(
                gcmd, tc.lookup_tool(number), axis)
            gcode.register_command(name, func, desc=desc)

    def activate(self):
        toolhead = self.printer.lookup_object('toolhead')
        gcode = self.printer.lookup_object('gcode')
        hotend_extruder = toolhead.get_extruder().name
        if self.extruder_name and self.extruder_name != hotend_extruder:
            gcode.run_script_from_command(
                "ACTIVATE_EXTRUDER EXTRUDER='%s'" % (self.extruder_name,))
        hotend_extruder = toolhead.get_extruder().name
        if self.extruder_stepper and hotend_extruder:
                gcode.run_script_from_command(
                    "SYNC_EXTRUDER_MOTION EXTRUDER='%s' MOTION_QUEUE=" % (hotend_extruder, ))
                gcode.run_script_from_command(
                    "SYNC_EXTRUDER_MOTION EXTRUDER='%s' MOTION_QUEUE='%s'" % (self.extruder_stepper_name, hotend_extruder, ))
        if self.fan:
            gcode.run_script_from_command(
                "ACTIVATE_FAN FAN='%s'" % (self.fan.name,))
    def deactivate(self):
        if self.extruder_stepper:
            toolhead = self.printer.lookup_object('toolhead')
            gcode = self.printer.lookup_object('gcode')
            hotend_extruder = toolhead.get_extruder().name
            gcode.run_script_from_command(
                "SYNC_EXTRUDER_MOTION EXTRUDER='%s' MOTION_QUEUE=" % (self.extruder_stepper_name,))
            gcode.run_script_from_command(
                "SYNC_EXTRUDER_MOTION EXTRUDER='%s' MOTION_QUEUE=%s" % (hotend_extruder, hotend_extruder,))

    def _config_get(self, config, name, default_value):
        return config.get(name, self.toolchanger.config.get(name, default_value))
    def _config_getfloat(self, config, name, default_value):
        return config.getfloat(name, self.toolchanger.config.getfloat(name, default_value))
    def _config_getboolean(self, config, name, default_value):
        return config.getboolean(name, self.toolchanger.config.getboolean(name, default_value))

def load_config_prefix(config):
    return Tool(config)
