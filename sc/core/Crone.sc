// the Crone, a singleton class
// it receives OSC from *matron* and manages the current CroneEngine
Crone {
	// the audio server
	classvar <>server;
	// audio to disk recorder / player
	classvar <>recorder;
	classvar <>recorderState = 'init';
	classvar <>recordingsDir = "/home/pi/dust/audio/tape";
	classvar <>player;
	classvar <>playerClock;
	classvar <>playerState = 'init';
	classvar <>playerFile;
	// current CroneEngine subclass instance
	classvar <>engine;
	// available OSC functions
	classvar <>oscfunc;
	// address of remote client
	classvar <>remoteAddr;
	// port to send OSC on
	classvar <>txPort = 8888;
	// an AudioContext
	classvar <>ctx;
	// boot completion flag
	classvar complete = 0;

	// VU report thread
	classvar vuThread;
	// VU report interval
	classvar vuInterval;

	*initClass {
		StartUp.add { // defer until after sclang init

			postln("\n-------------------------------------------------");
			postln(" Crone startup");
			postln("");
			postln(" \OSC rx port: " ++ NetAddr.langPort);
			postln(" \OSC tx port: " ++ txPort);
			postln("--------------------------------------------------\n");

			// FIXME? matron address is hardcoded here
			remoteAddr =NetAddr("127.0.0.1", txPort);

			server = Server.local;
			server.options.memSize = 2**16;
			server.latency = 0.05;

			server.waitForBoot {
				CroneDefs.sendDefs(server);
				server.sync;
				// create the audio context (boilerplate routing and analysis)
				ctx = AudioContext.new(server);

				Crone.initOscRx;
				Crone.initVu;
				Crone.initTape;

				complete = 1;
			};

		}

	}

	*setEngine { arg name;
		var class;
		class = CroneEngine.allSubclasses.detect({ arg n; n.asString == name.asString });
		if(engine.class != class, {
			if(class.notNil, {
				fork {
					if(engine.notNil, {
						var cond = Condition.new(false);
						postln("free engine: " ++ engine);
						engine.deinit({ cond.test = true; cond.signal; });
						cond.wait;

					});
					class.new(ctx, {
						arg theEngine;
						postln("-----------------------");
						postln("-- crone: done loading engine, starting reports");
						postln("--------");

						this.engine = theEngine;
						postln("engine: " ++ this.engine);

						this.reportCommands;
						this.reportPolls;
					});
				}
			});
		}, {
			// if we didn't change engines, just resend the reports
			this.reportCommands;
			this.reportPolls;
		});

	}

	// start a thread to continuously send a named report with a given interval
	*startPoll { arg idx;
		var poll = CronePollRegistry.getPollFromIndex(idx);
		if(poll.notNil, {
			poll.start(remoteAddr);
		}, {
			postln("startPoll failed; couldn't find index " ++ idx);
		});
	}

	*stopPoll { arg idx;
		var poll = CronePollRegistry.getPollFromIndex(idx);
		if(poll.notNil, {
			poll.stop;
		}, {
			postln("stopPoll failed; couldn't find index " ++ idx);
		});
	}

	*setPollTime { arg idx, dt;
		var pt = CronePollRegistry.getPollFromIndex(idx);
		if(pt.notNil, {
			pt.setTime(dt);
		}, {
			postln("setPollTime failed; couldn't find index " ++ idx);
		});
	}

	
	*requestPollValue { arg idx;
		var poll = CronePollRegistry.getPollFromIndex(idx);
		if(poll.notNil, {
			poll.sendValue;
		});
	}

	*reportEngines {
		var names = CroneEngine.allSubclasses.select {
			|class| class.name.asString.beginsWith("Engine_");
		}.collect({ arg n;
			n.asString.split($_).drop(1).join($_)
		});
		postln('engines: ' ++ names);
		remoteAddr.sendMsg('/report/engines/start', names.size);
		names.do({ arg name, i;
			remoteAddr.sendMsg('/report/engines/entry', i, name);

		});
		remoteAddr.sendMsg('/report/engines/end');
	}

	*reportCommands {
		var commands = engine !? _.commands;
		remoteAddr.sendMsg('/report/commands/start', commands.size);
		commands.do({ arg cmd, i;
			postln('command entry: ' ++ [i, cmd.name, cmd.format]);
			remoteAddr.sendMsg('/report/commands/entry', i, cmd.name, cmd.format);
		});
		remoteAddr.sendMsg('/report/commands/end');
	}

	*reportPolls {
		var num = CronePollRegistry.getNumPolls;
		remoteAddr.sendMsg('/report/polls/start', num);
		num.do({ arg i;
			var poll = CronePollRegistry.getPollFromIndex(i);
			postln(poll.name);
			// FIXME: polls should just have format system like commands?
			remoteAddr.sendMsg('/report/polls/entry', i, poll.name, if(poll.type == \value, {0}, {1}));
		});

		remoteAddr.sendMsg('/report/polls/end');
	}

	*tapeNewfile { |filename|
		var prepareFunc = {
			recorder.prepareForRecord(recordingsDir +/+ filename, 2);
			server.sync;
			recorderState = 'prepared';
			postln("tape recorder state:" + recorderState);
			remoteAddr.sendMsg('/tape/rec/state', recorderState);
			remoteAddr.sendMsg('/tape/rec/filename', filename);
			postln("tape recorder filename:" + filename);
		};
		if (#[stopped, prepared, init].includes(recorderState).not) {
			fork {
				recorder.stopRecording;
				server.sync;
				recorderState = 'stopped';
				remoteAddr.sendMsg('/tape/rec/state', recorderState);
				postln("tape recorder state:" + recorderState);
				prepareFunc.fork;
			};
		} {
			prepareFunc.fork;
		};
	}

	*tapeStartRec {
		fork {
			switch (recorderState)
				{ 'prepared' } {
					recorder.record(bus: ctx.out_b, node: ctx.xg);
				}
				{ 'paused' } {
					recorder.record;
				} !? {
					server.sync;
					recorderState = 'recording';
					postln("tape recorder state:" + recorderState);
					remoteAddr.sendMsg('/tape/rec/state', recorderState);
				}
		};
	}

	*tapePauseRec {
		if (recorderState == \recording) {
			fork {
				recorder.pauseRecording;
				server.sync;
				recorderState = 'paused';
				postln("tape recorder state:" + recorderState);
				remoteAddr.sendMsg('/tape/rec/state', recorderState);
			};
		};
	}

	*tapeStopRec {
		if (#[recording, paused].includes(recorderState)) {
			fork {
				recorder.stopRecording;
				server.sync;
				recorderState = 'stopped';
				postln("tape recorder state:" + recorderState);
				remoteAddr.sendMsg('/tape/rec/state', recorderState);
			};
		};
	}

	*tapeOpenfile { |filename|
		if (PathName(recordingsDir +/+ filename).isFile) {
			if (#[playing, paused, fileopened].includes(playerState)) {
				player.stop;
			};
			fork {
				playerFile = filename;
				player = SoundFile(recordingsDir +/+ playerFile).cue(
					(
						out: ctx.out_b
					)
				);
				server.sync;
				playerClock.beats = 0;
				playerState = 'fileopened';
				postln("tape player state:" + playerState);
				remoteAddr.sendMsg('/tape/play/state', playerState);
				remoteAddr.sendMsg('/tape/play/filename', filename);
				postln("tape player filename:" + filename.quote);
			};
		} {
			postln("tape error, file" + filename.quote + "does not exist");
		};
	}

	*tapePlay { |filename|
		if (#[paused, fileopened].includes(playerState)) {
			fork {
				player.play;
				playerClock.beats = 0;
				server.sync;
				playerState = 'playing';
				postln("tape player state:" + playerState);
				remoteAddr.sendMsg('/tape/play/state', playerState);
			};
		};
	}

	*tapePause { |filename|
		if (playerState == 'playing') {
			fork {
				player.pause;
				server.sync;
				playerState = 'paused';
				postln("tape player state:" + playerState);
				remoteAddr.sendMsg('/tape/play/state', playerState);
			};
		};
	}

	*tapeReset { |filename| // this is a hack to get this going: file is closed, reopened and played from the beginning
		if (#[playing, paused].includes(playerState)) {
			fork {
				var stateWas = playerState;
				player.close;
				this.tapeOpenfile(playerFile);
				if (stateWas == 'playing') {
					0.1.wait;
					this.tapePlay;
				} {
					playerState == 'paused';
				}
			};
		};
	}

	*initVu {
		// VU levels are reported to a dedicated OSC address.
		vuInterval = 0.05;
		vuThread = Routine { inf.do {
			remoteAddr.sendMsg('/poll/vu', ctx.buildVuBlob);
			vuInterval.wait;
		}}.play;
	}

	*initOscRx {
		oscfunc = (

			// @module crone
			// @alias crone

			/// send a `/crone/ready` response if Crone is done starting up,
			/// otherwise send nothing
			// @function /ready
			'/ready':OSCFunc.new({
				arg msg, time, addr, recvPort;
				if(complete==1) {
					postln(">>> /crone/ready");
					remoteAddr.sendMsg('/crone/ready');
				}
			}, '/ready'),

			// @section report management

			/// begin OSC engine report sequence
			// @function /report/engines
			'/report/engines':OSCFunc.new({
				arg msg, time, addr, recvPort;
				this.reportEngines;
			}, '/report/engines'),

			/// begin OSC command report sequence
			// @function /report/commands
			'/report/commands':OSCFunc.new({
				arg msg, time, addr, recvPort;
				this.reportCommands;
			}, '/report/commands'),

			/// begin OSC poll report sequence
			// @function /report/polls
			'/report/polls':OSCFunc.new({
				arg msg, time, addr, recvPort;
				this.reportPolls;
			}, '/report/polls'),

			// @function /engine/free
			'/engine/free':OSCFunc.new({
				if(engine.notNil, { engine.free; });
			}, '/engine/free'),

			// @function /engine/load/name
			// @param engine name (string)
			'/engine/load/name':OSCFunc.new({
				arg msg, time, addr, recvPort;
				this.setEngine('Engine_' ++ msg[1]);
			}, '/engine/load/name'),

			// @function /poll/start
			// @param poll index (integer)
			'/poll/start':OSCFunc.new({
				arg msg, time, addr, recvPort;
				this.startPoll(msg[1]);
			}, '/poll/start'),

			// @function /poll/stop
			// @param poll index (integer)
			'/poll/stop':OSCFunc.new({
				arg msg, time, addr, recvPort;
				this.stopPoll(msg[1]);
			}, '/poll/stop'),

			/// set the period of a poll
			// @function /poll/time
			// @param poll index (integer)
			// @param poll period(float)
			'/poll/time':OSCFunc.new({
				arg msg, time, addr, recvPort;
				this.setPollTime(msg[1], msg[2]);
			}, '/poll/time'),

			
			/// set the period of a poll
			// @function /poll/request/value
			// @param poll index (integer)
			'/poll/value':OSCFunc.new({
				arg msg, time, addr, recvPort;
				this.requestPollValue(msg[1]);
			}, '/poll/value'),


			// @section AudioContext control

			// @function /audio/input/level
			// @param input channel (integer: 0 or 1)
			// @param level (float: [0, 1])
			'/audio/input/level':OSCFunc.new({
				arg msg, time, addr, recvPort;
				ctx.in_s[msg[1]].set(\level, msg[2]);
			}, '/audio/input/level'),

			// @function /audio/output/level
			// @param level (float)
			// @param level (float: [0, 1])
			'/audio/output/level':OSCFunc.new({
				arg msg, time, addr, recvPort;
				ctx.out_s.set(\level, msg[1]);
			}, '/audio/output/level'),

			// @function /audio/monitor/level
			// @param level (float: [0, 1])
			'/audio/monitor/level':OSCFunc.new({
				arg msg, time, addr, recvPort;
				ctx.monitorLevel(msg[1]);
			}, '/audio/monitor/level'),

			// @function /audio/monitor/mono
			'/audio/monitor/mono':OSCFunc.new({
				arg msg, time, addr, recvPort;
				ctx.monitorMono;
			}, '/audio/monitor/mono'),

			// @function /audio/monitor/stereo
			'/audio/monitor/stereo':OSCFunc.new({
				arg msg, time, addr, recvPort;
				ctx.monitorStereo;
			}, '/audio/monitor/stereo'),

			// toggle monitoring altogether (will cause clicks)
			// @function /audio/monitor/on
			'/audio/monitor/on':OSCFunc.new({
				arg msg, time, addr, recvPort;
				ctx.monitorOn;
			}, '/audio/monitor/on'),

			// @function /audio/monitor/off
			'/audio/monitor/off':OSCFunc.new({
				arg msg, time, addr, recvPort;
				ctx.monitorOff;
			}, '/audio/monitor/off'),

			// toggle pitch analysis (save CPU)
			// @function /audio/pitch/on
			'/audio/pitch/on':OSCFunc.new({
				arg msg, time, addr, recvPort;
				ctx.pitchOn;
			}, '/audio/pitch/on'),

			// @function /audio/pitch/off
			'/audio/pitch/off':OSCFunc.new({
				arg msg, time, addr, recvPort;
				ctx.pitchOff;
			}, '/audio/pitch/off'),

			// recompile the sclang library!
			'/recompile':OSCFunc.new({
				postln("recompile...");
				thisProcess.recompile;
			}, '/recompile'),

			// @section tape

			/// determines file to record
			// @function /tape/newfile
			// @param filename (string)
			'/tape/newfile':OSCFunc.new({
				arg msg, time, addr, recvPort;
				this.tapeNewfile(msg[1]);
			}, '/tape/newfile'),

			/// start / resume recording
			// @function /tape/start_rec
			'/tape/start_rec':OSCFunc.new({
				arg msg, time, addr, recvPort;
				this.tapeStartRec;
			}, '/tape/start_rec'),

			/// pause recording
			// @function /tape/pause_rec
			'/tape/pause_rec':OSCFunc.new({
				arg msg, time, addr, recvPort;
				this.tapePauseRec;
			}, '/tape/pause_rec'),

			/// stop recording and close file
			// @function /tape/stop_rec
			'/tape/stop_rec':OSCFunc.new({
				arg msg, time, addr, recvPort;
				this.tapeStopRec;
			}, '/tape/stop_rec'),

			/// determines file to play
			// @function /tape/openfile
			// @param path (string)
			'/tape/openfile':OSCFunc.new({
				arg msg, time, addr, recvPort;
				this.tapeOpenfile(msg[1]);
			}, '/tape/openfile'),

			/// starts playing file
			// @function /tape/play
			'/tape/play':OSCFunc.new({
				arg msg, time, addr, recvPort;
				this.tapePlay;
			}, '/tape/play'),

			/// pauses playing file
			// @function /tape/pause
			'/tape/pause':OSCFunc.new({
				arg msg, time, addr, recvPort;
				this.tapePause;
			}, '/tape/pause'),

			/// reset playpos to 0
			// @function /tape/stop
			'/tape/reset':OSCFunc.new({
				arg msg, time, addr, recvPort;
				this.tapeReset;
			}, '/tape/reset'),

		);

	}

	*initTape {
		recorder = Recorder.new(server);
		recorder.recSampleFormat = "int16";
		playerClock = TempoClock.new;

		CronePollRegistry.register(
			name: \tape_rec_dur,
			func: {
				recorder.duration
			},
			dt: 0.1,
			type: \value
		);
		CronePollRegistry.register(
			name: \tape_play_pos,
			func: {
				if (#[playing, paused].includes(playerState)) {
					playerClock.beats
				} {
					0
				};
			},
			dt: 0.1,
			type: \value
		);
	}
}

