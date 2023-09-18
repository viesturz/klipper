# Support for toolchnagers
#
# Copyright (C) 2023 Viesturs Zarins <viesturz@gmail.com>
#
# This file may be distributed under the terms of the GNU GPLv3 license.

import ast, bisect

STATUS_UNINITALIZED = 'uninitialized'
STATUS_INITIALIZING = 'initializing'
STATUS_READY = 'ready'
STATUS_CHANGING = 'changing'
STATUS_ERROR = 'error'
INIT_ON_HOME = 0
INIT_MANUAL = 1
INIT_FIRST_USE = 2
XYZ_TO_INDEX = {'x': 0, 'X': 0, 'y': 1, 'Y': 1, 'z': 2, 'Z': 2}

class Toolchanger:
    def __init__(self, config):
        self.printer = config.get_printer()
        self.config = config
        self.gcode_macro = self.printer.load_object(config, 'gcode_macro')
        self.gcode = self.printer.lookup_object('gcode')
        self.gcode_move = self.printer.lookup_object('gcode_move')

        self.name = config.get_name()
        self.params = get_params_dict(config)
        self.clear_gcode_offset_for_toolchange = config.getboolean(
            'clear_gcode_offset_for_toolchange', True)
        init_options = {'home': INIT_ON_HOME,
                        'manual': INIT_MANUAL, 'first-use': INIT_FIRST_USE}
        self.initialize_on = config.getchoice(
            'initialize_on', init_options, 'first-use')
        self.initialize_gcode = self.gcode_macro.load_template(
            config, 'initialize_gcode', '')
        self.before_change_gcode = self.gcode_macro.load_template(
            config, 'before_change_gcode', '')
        self.after_change_gcode = self.gcode_macro.load_template(
            config, 'after_change_gcode', '')

        self.status = STATUS_UNINITALIZED
        self.active_tool = None
        self.tools = {}
        self.tool_numbers = [] # Ordered list of registered tool numbers.
        self.tool_names = [] # Tool names, in the same order as numbers.
        self.error_message = ''

        self.printer.register_event_handler("homing:home_rails_begin",
                                            self._handle_home_rails_begin)
        self.gcode.register_command("INITIALIZE_TOOLCHANGER",
                                    self.cmd_INITIALIZE_TOOLCHANGER,
                                    desc=self.cmd_INITIALIZE_TOOLCHANGER_help)
        self.gcode.register_command("SELECT_TOOL",
                                   self.cmd_SELECT_TOOL,
                                   desc=self.cmd_SELECT_TOOL_help)
        self.gcode.register_command("SELECT_TOOL_ERROR",
                                    self.cmd_SELECT_TOOL_ERROR,
                                    desc=self.cmd_SELECT_TOOL_ERROR_help)
        self.gcode.register_command("UNSELECT_TOOL",
                                    self.cmd_UNSELECT_TOOL,
                                    desc=self.cmd_UNSELECT_TOOL_help)

    def _handle_home_rails_begin(self, homing_state, rails):
        if self.initialize_on == INIT_ON_HOME and self.status == STATUS_UNINITALIZED:
            self.initialize()

    def get_status(self, eventtime):
        return {'name': self.name,
                'status': self.status,
                'tool': self.active_tool.name if self.active_tool else None,
                'tool_number': self.active_tool.tool_number if self.active_tool else -1,
                'tool_numbers': self.tool_numbers,
                'tool_names': self.tool_names,
                } | self.params

    def assign_tool(self, tool, number, prev_number, replace = False):
        if number in self.tools and not replace:
            raise Exception('Duplicate tools with number %s' % (str(number)))
        if prev_number in self.tools:
            del self.tools[prev_number]
            self.tool_numbers.remove(prev_number)
            self.tool_names.remove(tool.name)
        self.tools[number] = tool
        position = bisect.bisect_left(self.tool_numbers, number)
        self.tool_numbers.insert(position, number)
        self.tool_names.insert(position, tool.name)

    cmd_INITIALIZE_TOOLCHANGER_help = "Initialize the toolchanger"
    def cmd_INITIALIZE_TOOLCHANGER(self, gcmd):
        tool_name = gcmd.get('TOOL', None)
        tool_number = gcmd.get_int('T', None)
        tool = None
        if tool_name:
            tool = self.printer.lookup_object(tool_name)
        if tool_number is not None:
            tool = self.lookup_tool(tool_number)
            if not tool:
                raise gcmd.error('Tool #%d is not assigned' % (tool_number))
        self.initialize(tool)

    cmd_SELECT_TOOL_help = 'Select active tool'
    def cmd_SELECT_TOOL(self, gcmd):
        restore_axis = gcmd.get('RESTORE_AXIS', 'XYZ')
        tool_name = gcmd.get('TOOL', None)
        if tool_name:
            tool = self.printer.lookup_object(tool_name)
            self.select_tool(gcmd, tool, restore_axis)
            return
        tool_nr = gcmd.get_int('T', None)
        if tool_nr is not None:
            tool = self.lookup_tool(tool_nr)
            if not tool:
                raise gcmd.error("Select tool: T%d not found" % (tool_nr))
            self.select_tool(gcmd, tool, restore_axis)
            return
        raise gcmd.error("Select tool: Either TOOL or T needs to be specified")

    cmd_SELECT_TOOL_ERROR_help = "Abort tool change and mark the active toolchanger as failed"
    def cmd_SELECT_TOOL_ERROR(self, gcmd):
        if self.status != STATUS_CHANGING and self.status != STATUS_INITIALIZING:
            gcmd.respond_info(
                'SELECT_TOOL_ERROR called while not selecting, doing nothing')
            return
        self.status = STATUS_ERROR
        self.error_message = gcmd.get('MESSAGE', '')

    cmd_UNSELECT_TOOL_help = "Unselect active tool without selecting a new one"
    def cmd_UNSELECT_TOOL(self, gcmd):
        restore_axis = gcmd.get('RESTORE_AXIS', '')
        self.select_tool(gcmd, None, restore_axis)

    def initialize(self, select_tool=None):
        if self.status == STATUS_CHANGING:
            raise Exception('Cannot initialize while changing tools')

        # Initialize may be called from within the intialize gcode
        # to set active tool without performing a full change
        should_run_initialize = self.status != STATUS_INITIALIZING

        if should_run_initialize:
            self.status = STATUS_INITIALIZING
            self.run_gcode('initialize_gcode', self.initialize_gcode, {})

        if select_tool:
            self._configure_toolhead_for_tool(select_tool)
            self.run_gcode('after_change_gcode', self.after_change_gcode, {})
            self._set_tool_gcode_offset(select_tool)

        if should_run_initialize:
            self.status = STATUS_READY
            self.gcode.respond_info('%s initialized, active %s' %
                                    (self.name, self.active_tool.name if self.active_tool else None))

    def select_tool(self, gcmd, tool, restore_axis):
        if self.status == STATUS_UNINITALIZED and self.initialize_on == INIT_FIRST_USE:
            self.initialize()
        if self.status != STATUS_READY:
            raise gcmd.error(
                "Cannot select tool, toolchanger status is " + self.status)

        if self.active_tool == tool:
            gcmd.respond_info('Tool %s already selected' % tool.name if tool else None)
            return

        self.status = STATUS_CHANGING
        gcode_position = self.gcode_move.get_gcode_position()
        extra_context = {
            'dropoff_tool': self.active_tool.name if self.active_tool else None,
            'pickup_tool': tool.name if tool else None,
        }

        self.gcode.run_script_from_command(
            "SAVE_GCODE_STATE NAME=_toolchange_state")

        self.run_gcode('before_change_gcode',
                       self.before_change_gcode, extra_context)
        if self.clear_gcode_offset_for_toolchange:
            self.gcode.run_script_from_command(
                "SET_GCODE_OFFSET X=0.0 Y=0.0 Z=0.0")

        if self.active_tool:
            self.run_gcode('tool.dropoff_gcode',
                           self.active_tool.dropoff_gcode, extra_context)

        if tool is not None:
            self._configure_toolhead_for_tool(tool)
            self.run_gcode('tool.pickup_gcode',
                           tool.pickup_gcode, extra_context)
            self.run_gcode('after_change_gcode',
                           self.after_change_gcode, extra_context)

        # Set new offsets so that restore axis takes them into account.
        self._set_tool_gcode_offset(tool)
        self._restore_axis(gcode_position, restore_axis)

        self.gcode.run_script_from_command(
            "RESTORE_GCODE_STATE NAME=_toolchange_state MOVE=0")
        # Restore state sets old gcode offsets, fix that.
        if tool is not None:
            self._set_tool_gcode_offset(tool)

        self.status = STATUS_READY
        if tool:
            gcmd.respond_info('Selected tool %s (%s)' % (str(tool.tool_number), tool.name))
        else:
            gcmd.respond_info('Tool unselected')

    def lookup_tool(self, number):
        return self.tools.get(number, None)

    def get_selected_tool(self):
        return self.active_tool

    def _configure_toolhead_for_tool(self, tool):
        if self.active_tool:
            self.active_tool.deactivate()
        self.active_tool = tool
        if self.active_tool:
            self.active_tool.activate()

    def _set_tool_gcode_offset(self, tool):
        if tool is None:
            return
        if tool.gcode_x_offset is None and tool.gcode_y_offset is None and tool.gcode_z_offset is None:
            return
        cmd = 'SET_GCODE_OFFSET'
        if tool.gcode_x_offset is not None:
            cmd += ' X=%f' % (tool.gcode_x_offset,)
        if tool.gcode_y_offset is not None:
            cmd += ' Y=%f' % (tool.gcode_y_offset,)
        if tool.gcode_z_offset is not None:
            cmd += ' Z=%f' % (tool.gcode_z_offset,)
        self.gcode.run_script_from_command(cmd)
        mesh = self.printer.lookup_object('bed_mesh')
        if mesh and mesh.get_mesh():
            self.gcode.run_script_from_command('BED_MESH_OFFSET X=%.6f Y=%.6f' %
                                                (-tool.gcode_x_offset, -tool.gcode_y_offset))

    def _restore_axis(self, position, axis):
        if not axis:
            return
        cmd = 'G0'
        for i in axis:
            cmd += ' %s%.6f' % (i, position[XYZ_TO_INDEX[i]])
        self.gcode.run_script_from_command(cmd)

    def run_gcode(self, name, template, extra_context={}):
        current_status = self.status
        curtime = self.printer.get_reactor().monotonic()
        try:
            context = template.create_template_context() | extra_context
            context['tool'] = self.active_tool.get_status(curtime) if self.active_tool else {}
            context['toolchanger'] = self.get_status(curtime)
            template.run_gcode_from_command(context)
        except Exception as e:
            raise Exception("Script running error: %s" % (str(e)))
        if current_status != self.status:
            raise Exception("Unexpected status during %s, status = %s, message = %s, aborting" % (
                name, self.status, self.error_message))

def get_params_dict(config):
    result = {}
    for option in config.get_prefix_options('params_'):
        try:
            result[option] = ast.literal_eval(config.get(option))
        except ValueError as e:
            raise config.error(
                "Option '%s' in section '%s' is not a valid literal" % (
                    option, config.get_name()))
    return result

def load_config(config):
    return Toolchanger(config)

def load_config_prefix(config):
    return Toolchanger(config)
