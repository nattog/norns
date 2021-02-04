--- passthru
-- midi routing
--
-- @module lib.passthru
-- @release v1.0.0
-- @author nattog

-- dependencies
local MusicUtil = require "musicutil"

-- passthru-specific vars
local passthru = {}
local devices = {}
local midi_device
local midi_interface
local clock_device
local quantize_midi
local scale_names = {}
local current_scale = {}
local midi_notes = {}
local cc_directions = {"D --> I", "D <--> I"}

-------- Public methods --------

--- Host script callbacks for midi device events
function passthru:user_device_event(data)
end

--- Host script callbacks for midi interface events
function passthru:user_interface_event(data)
end

-------- Private methods --------

--- Handles a device event, sends data to interface
local function device_event(data)
    if #data == 0 then
        return
    end
    local msg = midi.to_msg(data)

    local device_ch_param = params:get("device_channel")
    local device_chan = device_ch_param > 1 and (device_ch_param - 1) or msg.ch

    local interface_ch_param = params:get("interface_channel")
    local interface_chan = interface_ch_param > 1 and (interface_ch_param - 1) or msg.ch

    if msg and msg.ch == device_chan then
        local note = msg.note

        if msg.note ~= nil then
            if quantize_midi == true then
                note = MusicUtil.snap_note_to_array(note, current_scale)
            end
        end

        if msg.type == "note_off" then
            midi_interface:note_off(note, 0, interface_chan)
        elseif msg.type == "note_on" then
            midi_interface:note_on(note, msg.vel, interface_chan)
        elseif msg.type == "key_pressure" then
            midi_interface:key_pressure(note, msg.val, interface_chan)
        elseif msg.type == "channel_pressure" then
            midi_interface:channel_pressure(msg.val, interface_chan)
        elseif msg.type == "pitchbend" then
            midi_interface:pitchbend(msg.val, interface_chan)
        elseif msg.type == "program_change" then
            midi_interface:program_change(msg.val, interface_chan)
        elseif msg.type == "cc" then
            midi_interface:cc(msg.cc, msg.val, interface_chan)
        end
    end

    passthru.user_device_event(data)
end

--- Handles an interface event, sends data to device
local function interface_event(data)
    local msg = midi.to_msg(data)
    local note = msg.note

    if clock_device then
        if msg.type == "clock" then
            midi_device:clock()
        elseif msg.type == "start" then
            midi_device:start()
        elseif msg.type == "stop" then
            midi_device:stop()
        elseif msg.type == "continue" then
            midi_device:continue()
        end
    end
    if params:get("cc_direction") == 2 then
        local device_ch_param = params:get("device_channel")
        local device_chan = device_ch_param > 1 and (device_ch_param - 1) or msg.ch

        if msg.type == "cc" then
            midi_device:cc(msg.cc, msg.val, device_chan)
        end
    end

    passthru.user_interface_event(data)
end

-- generates user selected scale from root note
local function build_scale()
    current_scale = MusicUtil.generate_scale_of_length(params:get("root_note"), params:get("scale_mode"), 128)
end

-- fetches available midi devices for displaying names
local function get_midi_devices()
    d = {}
    for id, device in pairs(midi.vports) do
        d[id] = device.name
    end
    return d
end

-- initialise, param setup
function passthru.init()

    clock_device = false
    quantize_midi = false

    -- connect midi devices
    midi_device = midi.connect(1)
    midi_device.event = device_event
    midi_interface = midi.connect(2)
    midi_interface.event = interface_event

    -- get device names for param setup
    devices = get_midi_devices()
    
    -- generate scale names for param setup
    for i = 1, #MusicUtil.SCALES do
        table.insert(scale_names, string.lower(MusicUtil.SCALES[i].name))
    end

    -- PARAMS --
    params:add_group("PASSTHRU", 9)
    params:add {
        type = "option",
        id = "midi_device",
        name = "Device",
        options = devices,
        default = 1,
        action = function(value)
            midi_device.event = nil
            midi_device = midi.connect(value)
            midi_device.event = device_event
        end
    }

    params:add {
        type = "option",
        id = "midi_interface",
        name = "Interface",
        options = devices,
        default = 2,
        action = function(value)
            midi_interface.event = nil
            midi_interface = midi.connect(value)
            midi_interface.event = interface_event
        end
    }

    params:add {
        type = "option",
        id = "cc_direction",
        name = "CC msg direction",
        options = cc_directions,
        default = 1
    }

    local channels = {"No change"}
    for i = 1, 16 do
        table.insert(channels, i)
    end
    params:add {
        type = "option",
        id = "device_channel",
        name = "Device channel",
        options = channels,
        default = 1
    }

    channels[1] = "Device src."
    params:add {
        type = "option",
        id = "interface_channel",
        name = "Interface channel",
        options = channels,
        default = 1
    }

    params:add {
        type = "option",
        id = "clock_device",
        name = "Clock device",
        options = {"no", "yes"},
        action = function(value)
            clock_device = value == 2
            if value == 1 then
                midi_device:stop()
            end
        end
    }

    params:add {
        type = "option",
        id = "quantize_midi",
        name = "Quantize",
        options = {"no", "yes"},
        action = function(value)
            quantize_midi = value == 2
            build_scale()
        end
    }

    params:add {
        type = "option",
        id = "scale_mode",
        name = "Scale",
        options = scale_names,
        default = 5,
        action = function()
            build_scale()
        end
    }

    params:add {
        type = "number",
        id = "root_note",
        name = "Root",
        min = 0,
        max = 11,
        default = 0,
        formatter = function(param)
            return MusicUtil.note_num_to_name(param:get())
        end,
        action = function()
            build_scale()
        end
    }

    -- expose device and interface connections
    passthru.device = midi_device
    passthru.interface = midi_interface
end

return passthru