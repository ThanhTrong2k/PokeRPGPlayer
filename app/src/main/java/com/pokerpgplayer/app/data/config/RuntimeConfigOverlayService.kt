package com.pokerpgplayer.app.data.config

import com.pokerpgplayer.app.data.model.KnownMitigationIds
import com.pokerpgplayer.app.data.model.MitigationAudit
import com.pokerpgplayer.app.data.model.OverlayGenerationTarget
import com.pokerpgplayer.app.data.model.OverlayStatus
import com.pokerpgplayer.app.data.model.RuntimeConfigProfile
import java.io.File
import java.security.MessageDigest
import java.text.Normalizer

/**
 * Sprint 25 — Runtime Config Safety Layer, Stage 2: isolated overlay
 * generation. Reads an original/mirrored `mkxp.json`'s own text and a
 * [RuntimeConfigProfile], applies whichever known mitigations the
 * profile's own [RuntimeConfigProfile.enabledMitigations] call for
 * (minus anything in [RuntimeConfigProfile.disabledMitigations]), and
 * returns a disposable overlay config plus an audit trail — matching the
 * Sprint 24 ADR's own "Runtime Config Overlay" architecture and its
 * answer to question 5 (audit) and question 6 (override/rollback).
 *
 * **This class is explicitly not wired into any launch path.** Nothing
 * in `RuntimeActivity` or anywhere else calls this service yet — that is
 * Stage 3's own, separately-approved responsibility. This class exists
 * to prove the generation logic is correct in isolation first, matching
 * this project's own established plan→approve→implement discipline.
 *
 * **This is also explicitly not the same thing as the diagnostic
 * `preloadScript` probes built in Sprint 20–23** (`androidTest`-scoped,
 * hardcoded to one script and one game). This service is generic and
 * signal-gated — it applies whatever [RuntimeConfigProfile.enabledMitigations]
 * says, for any game, using the known mitigation catalog in
 * [KnownMitigationIds] — not a reuse of the diagnostic tests' own
 * one-off logic.
 *
 * **Never parses `mkxp.json` with a strict JSON parser.** mkxp-z's own
 * config format is JSON5-tolerant (comments, trailing commas — confirmed
 * directly from `config.cpp`'s own `json::parse5` usage since Sprint
 * 16), which is exactly why `org.json.JSONObject` was rejected for this
 * purpose in App v0.0.25 (`JSONException: Expected literal value`,
 * fixed in App v0.0.26 with the same text-based approach this service
 * now uses as its own, production-quality implementation). Every
 * transformation here operates on the config's own raw text, preserving
 * every comment, trailing comma, and formatting choice outside the
 * specific value being changed.
 *
 * **Never mutates the original config.** [generateOverlay] takes the
 * original config's own text as a plain [String] parameter — it has no
 * way to write back to wherever that text came from, by construction.
 * [generateOverlayToDirectory] is the only method that touches a real
 * [File], and it only ever *reads* the original and *writes* to a
 * separate `outputDirectory` — never back to the original file's own path.
 */
class RuntimeConfigOverlayService {

    companion object {
        /** File name for the generated overlay config, written into whatever output directory is given. */
        const val OVERLAY_CONFIG_FILE_NAME = "mkxp.json"

        /** File name for the generated Zlib preload script, matching the approved Sprint 25 scope exactly. */
        const val ZLIB_PRELOAD_SCRIPT_FILE_NAME = "pokerpg_preload_zlib.rb"

        /** Content of the generated Zlib preload script — exactly as specified: `require 'zlib'`, no raise, no `PluginManager` reference. */
        const val ZLIB_PRELOAD_SCRIPT_CONTENT = "require 'zlib'\n"

        /** Sprint 41 — temporary Ruby diagnostic preload script, generated only for the disposable overlay workspace. */
        private const val SPRINT41_DIAGNOSTIC_SCRIPT_FILE_NAME = "sprint41-input-diagnostic.rb"

        /** Sprint 41 — embedded Ruby movement diagnostic. Kotlin-dollar escaped during generation. */
        private val SPRINT41_DIAGNOSTIC_SCRIPT_CONTENT = """
# ============================================================
# Sprint 41 (refined) — TEMPORARY, DISPOSABLE Ruby diagnostic overlay.
# ============================================================
# THIS FILE MODIFIES NOTHING PERMANENT. Injected via the Runtime
# Config Overlay's existing preloadScript mechanism (same pattern
# zlib-preload has used since Sprint 20-26) — never touches
# Scripts.rxdata or any other original game file. Disabling the
# sprint41-input-diagnostic mitigation removes every trace of this
# instantly and completely. Every hook uses alias_method (never a
# wholesale redefinition), so original behavior is always fully
# preserved underneath the added logging.
#
# WHY A "WATCHER" INSTEAD OF PATCHING Game_Player DIRECTLY HERE:
# preloadScript runs BEFORE Scripts.rxdata loads, so Game_Player
# does not exist yet at this point. Graphics.update (a native
# mkxp-z binding, always available regardless of Scripts.rxdata
# content) is wrapped as a safe "watcher": every frame, it checks
# whether Game_Player has become defined yet, and installs the real
# hooks the moment it does.
# ============================================================

module Sprint41Diag
  TAG = "SPRINT41_DIAG"
  LOG_FILE_NAME = "sprint41_diag_runtime.log"

  @installed = false
  @watcher_started_frame = nil
  @last_heartbeat_frame = 0
  @last_idle_log_frame = 0
  @never_defined_warned = false

  HEARTBEAT_THROTTLE_FRAMES     = 120  # requirement 1
  IDLE_LOG_THROTTLE_FRAMES      = 300  # requirement 3 — heavy throttle when dir4 == 0
  NEVER_DEFINED_TIMEOUT_FRAMES  = 1800 # ~30s at 60fps — requirement 7

  # Standard RGSS numpad-direction convention (confirmed consistent
  # with Sprint 40's own real dir4Data.active evidence: 2/4/6/8 for
  # Down/Left/Right/Up).
  DIRECTION_NAMES = { 2 => "Down", 4 => "Left", 6 => "Right", 8 => "Up", 0 => "None" }

  def self.log(message)
    line = "#{TAG}: #{message}"

    begin
      File.open(LOG_FILE_NAME, "a") { |f| f.puts(line) }
    rescue => e
      # Never allow diagnostic logging failure to affect gameplay.
    end

    begin
      puts line
    rescue => e
      # stdout/logcat forwarding is optional and not trusted on Android.
    end
  end

  # ---- Defensive helpers — never raise, always return something loggable ----

  def self.safe_call(obj, method_name, *args)
    return "N/A" if obj.nil?
    return "N/A" unless obj.respond_to?(method_name)
    obj.send(method_name, *args)
  rescue => e
    "ERROR(#{e.class}: #{e.message})"
  end

  def self.dir_name(d)
    DIRECTION_NAMES[d] || "Unknown(#{d})"
  end

  def self.message_showing_state
    return safe_call(${'$'}game_message, :visible) if defined?(${'$'}game_message) && ${'$'}game_message.respond_to?(:visible)
    return safe_call(${'$'}game_temp, :message_window_showing) if defined?(${'$'}game_temp) && ${'$'}game_temp.respond_to?(:message_window_showing)
    "N/A"
  end

  def self.interpreter_running_state
    return "N/A" unless defined?(${'$'}game_map) && ${'$'}game_map.respond_to?(:interpreter)
    safe_call(${'$'}game_map.interpreter, :running?)
  end

  def self.passable_precheck(player, direction)
    return "N/A" unless player.respond_to?(:passable?)
    x = safe_call(player, :x)
    y = safe_call(player, :y)
    return "N/A" if x == "N/A" || y == "N/A"
    safe_call(player, :passable?, x, y, direction)
  end

  # ---- Watcher: runs every frame from the moment this file loads ----

  def self.watcher_tick
    @watcher_started_frame ||= Graphics.frame_count
    return if @installed

    frames_waited = Graphics.frame_count - @watcher_started_frame

    # Requirement 1 — heartbeat every ~120 frames while still waiting.
    if Graphics.frame_count - @last_heartbeat_frame >= HEARTBEAT_THROTTLE_FRAMES
      log("watcher alive (frame=#{Graphics.frame_count}, waited=#{frames_waited} frames) — Game_Player not yet defined.")
      @last_heartbeat_frame = Graphics.frame_count
    end

    # Requirement 7 — explicit, one-time warning if Game_Player never appears.
    if !@never_defined_warned && frames_waited >= NEVER_DEFINED_TIMEOUT_FRAMES
      log("WARNING — Game_Player still not defined after #{frames_waited} frames (~#{NEVER_DEFINED_TIMEOUT_FRAMES / 60}s at 60fps). Hooks will never install unless this changes.")
      @never_defined_warned = true
    end

    try_install
  end

  def self.try_install
    return if @installed
    return unless defined?(Game_Player)
    @installed = true

    log("Game_Player now defined (frame=#{Graphics.frame_count}) — installing movement diagnostics.")

    # Requirement 2 — explicit, one-time existence check, logged either way.
    has_move_by_input = Game_Player.method_defined?(:move_by_input)
    if has_move_by_input
      log("move_by_input exists on Game_Player — will be hooked.")
    else
      # Requirement 6 — explicit, one-time "missing" log.
      log("move_by_input does NOT exist on Game_Player under this name — this build routes input differently, or via another method entirely.")
    end

    Game_Player.class_eval do
      # ---- update: requirement 3 (heavy-throttled idle) + requirement 4 (detailed active) ----
      unless method_defined?(:sprint41_orig_update)
        alias_method :sprint41_orig_update, :update
        define_method(:update) do
          Sprint41Diag.log_update_state(self)
          sprint41_orig_update
        end
      end

      if has_move_by_input && !method_defined?(:sprint41_orig_move_by_input)
        alias_method :sprint41_orig_move_by_input, :move_by_input
        define_method(:move_by_input) do
          Sprint41Diag.log("Game_Player#move_by_input CALLED (frame=#{Graphics.frame_count}) Input.dir4=#{Input.dir4}")
          sprint41_orig_move_by_input
        end
      end

      # Requirement 5 — full before/after detail on each directional move method.
      { move_down: 2, move_left: 4, move_right: 6, move_up: 8 }.each do |dir_method, dir_const|
        aliased_name = :"sprint41_orig_#{dir_method}"
        next unless method_defined?(dir_method)
        next if method_defined?(aliased_name)

        alias_method aliased_name, dir_method
        define_method(dir_method) do |*args|
          x_before = Sprint41Diag.safe_call(self, :x)
          y_before = Sprint41Diag.safe_call(self, :y)
          dir_before = Sprint41Diag.safe_call(self, :direction)
          passable_result = Sprint41Diag.passable_precheck(self, dir_const)

          Sprint41Diag.log(
            "Game_Player##{dir_method} CALLED args=#{args.inspect} " \
            "x_before=#{x_before} y_before=#{y_before} direction_before=#{dir_before}(#{Sprint41Diag.dir_name(dir_before)}) " \
            "passable?=#{passable_result}"
          )

          result = send(aliased_name, *args)

          x_after = Sprint41Diag.safe_call(self, :x)
          y_after = Sprint41Diag.safe_call(self, :y)
          dir_after = Sprint41Diag.safe_call(self, :direction)

          Sprint41Diag.log(
            "Game_Player##{dir_method} RETURNED " \
            "x_after=#{x_after} y_after=#{y_after} direction_after=#{dir_after}(#{Sprint41Diag.dir_name(dir_after)})"
          )

          result
        end
      end
    end
  end

  # Requirement 3 (idle) + Requirement 4 (active) — same call site, two throttle rates.
  def self.log_update_state(player)
    input_dir = Input.dir4
    frame = Graphics.frame_count

    if input_dir == 0
      return if frame - @last_idle_log_frame < IDLE_LOG_THROTTLE_FRAMES
      @last_idle_log_frame = frame
      log("Game_Player#update idle (frame=#{frame}) Input.dir4=0")
      return
    end

    # Active hold — requirement 4, full detail, not throttled beyond
    # once per call (holding a direction naturally produces one
    # update() call per frame; this is the state we most need dense
    # coverage of).
    x = safe_call(player, :x)
    y = safe_call(player, :y)
    direction = safe_call(player, :direction)
    moving = safe_call(player, :moving?)
    locked = safe_call(player, :lock?)
    route_forcing = safe_call(player, :move_route_forcing)

    log(
      "Game_Player#update ACTIVE (frame=#{frame}) Input.dir4=#{input_dir}(#{dir_name(input_dir)}) " \
      "x=#{x} y=#{y} direction=#{direction}(#{dir_name(direction)}) " \
      "moving?=#{moving} locked?=#{locked} move_route_forcing=#{route_forcing} " \
      "message_showing=#{message_showing_state} interpreter_running=#{interpreter_running_state}"
    )
  end
end

class << Graphics
  unless method_defined?(:sprint41_orig_update)
    alias_method :sprint41_orig_update, :update
    define_method(:update) do
      Sprint41Diag.watcher_tick
      sprint41_orig_update
    end
  end
end

Sprint41Diag.log("diagnostic preload script loaded — file logger initialized; watching for Game_Player definition.")

        """.trimIndent()

        /** Sprint 42 — temporary Ruby diagnostic preload script for movement method-path discovery. */
        private const val SPRINT42_DIAGNOSTIC_SCRIPT_FILE_NAME = "sprint42-movement-path-diagnostic.rb"

        /** Sprint 42 — embedded Ruby movement path diagnostic. Kotlin-dollar escaped during generation. */
        private val SPRINT42_DIAGNOSTIC_SCRIPT_CONTENT = """
# ============================================================
# Sprint 42 — TEMPORARY, DISPOSABLE Ruby diagnostic overlay.
# Discovers the actual Essentials v21.1 player movement method path.
# ============================================================
# THIS FILE MODIFIES NOTHING PERMANENT. Injected via the Runtime
# Config Overlay's existing preloadScript mechanism (same pattern
# zlib-preload/sprint41 have used) — never touches Scripts.rxdata or
# any other original game file. Disabling the sprint42-movement-path
# mitigation removes every trace of this instantly and completely.
# Every hook uses alias_method (never a wholesale redefinition), so
# original behavior is always fully preserved underneath the added
# logging.
#
# SPRINT 42 CONTEXT: Sprint 41's own file-backed diagnostic confirmed
# Input.dir4 IS correctly seen inside Game_Player#update (values 2/4/
# 6/8 for Down/Left/Right/Up, matching held D-Pad input exactly), but
# move_by_input does not exist on this build, and no evidence exists
# that move_down/move_left/move_right/move_up are ever called. This
# means Essentials v21.1 routes movement through a DIFFERENT method
# name than the standard RGSS2-style move_by_input this project
# assumed. Rather than guess further method names one at a time, this
# script (1) reflectively lists every candidate method on Game_Player
# matching several likely name patterns, with its owning class, and
# (2) hooks a broad list of specifically-named candidates, so the
# very next log pull should directly reveal which method (if any)
# actually reads Input.dir4 and attempts movement.
#
# WHY A "WATCHER" INSTEAD OF PATCHING DIRECTLY HERE: preloadScript
# runs BEFORE Scripts.rxdata loads, so Game_Player/Scene_Map/Game_Map
# do not exist yet at this point. Graphics.update (a native mkxp-z
# binding, always available) is wrapped as a safe "watcher": every
# frame, it checks whether each target class has become defined yet,
# and installs that class's own hooks the moment it does.
# ============================================================

module Sprint42Diag
  TAG = "SPRINT42_DIAG"
  LOG_FILE_NAME = "sprint42_movement_path_runtime.log"

  @game_player_installed = false
  @scene_map_installed = false
  @game_map_installed = false
  @watcher_started_frame = nil
  @last_heartbeat_frame = 0
  @last_idle_log_frame = 0
  @never_defined_warned = false

  HEARTBEAT_THROTTLE_FRAMES    = 120
  IDLE_LOG_THROTTLE_FRAMES     = 300
  NEVER_DEFINED_TIMEOUT_FRAMES = 1800

  DIRECTION_NAMES = { 2 => "Down", 4 => "Left", 6 => "Right", 8 => "Up", 0 => "None" }

  CANDIDATE_NAME_PATTERNS = [/move/, /input/, /update/, /pass/, /walk/, /direction/, /player/]

  CANDIDATE_HOOK_METHODS = [
    :update, :update_move, :update_nonmoving, :update_stop, :update_command,
    :check_event_trigger_here, :check_event_trigger_there,
    :movable?, :can_move?, :passable?,
    :move_generic, :move_straight, :move_forward,
    :move_down, :move_left, :move_right, :move_up
  ]

  # ---- Primary output: file, per Sprint 41.1's own established pattern ----

  def self.log(message)
    full_message = "#{TAG}: #{message}"
    begin
      File.open(LOG_FILE_NAME, "a") { |f| f.puts(full_message) }
    rescue => e
      # Never let a file-write failure crash the game.
    end
    begin
      puts full_message
    rescue => e
      # Secondary output only — never relied upon.
    end
  end

  # ---- Defensive helpers ----

  def self.safe_call(obj, method_name, *args)
    return "N/A" if obj.nil?
    return "N/A" unless obj.respond_to?(method_name)
    obj.send(method_name, *args)
  rescue => e
    "ERROR(#{e.class}: #{e.message})"
  end

  def self.dir_name(d)
    DIRECTION_NAMES[d] || "Unknown(#{d})"
  end

  def self.is_character_like?(obj)
    !obj.nil? && obj.respond_to?(:x) && obj.respond_to?(:y) && obj.respond_to?(:direction)
  end

  # ---- Watcher: runs every frame from the moment this file loads ----

  def self.watcher_tick
    @watcher_started_frame ||= Graphics.frame_count
    frames_waited = Graphics.frame_count - @watcher_started_frame

    all_installed = @game_player_installed && @scene_map_installed && @game_map_installed
    return if all_installed

    if Graphics.frame_count - @last_heartbeat_frame >= HEARTBEAT_THROTTLE_FRAMES
      log("watcher alive (frame=#{Graphics.frame_count}, waited=#{frames_waited}) — " \
          "Game_Player installed=#{@game_player_installed} Scene_Map installed=#{@scene_map_installed} Game_Map installed=#{@game_map_installed}")
      @last_heartbeat_frame = Graphics.frame_count
    end

    if !@never_defined_warned && frames_waited >= NEVER_DEFINED_TIMEOUT_FRAMES
      log("WARNING — not all target classes defined after #{frames_waited} frames (~#{NEVER_DEFINED_TIMEOUT_FRAMES / 60}s). " \
          "Game_Player=#{defined?(Game_Player).nil? ? 'undefined' : 'defined'} " \
          "Scene_Map=#{defined?(Scene_Map).nil? ? 'undefined' : 'defined'} " \
          "Game_Map=#{defined?(Game_Map).nil? ? 'undefined' : 'defined'}")
      @never_defined_warned = true
    end

    try_install_game_player
    try_install_scene_map
    try_install_game_map
  end

  # ---- Generic hook installer, reused for every candidate method ----
  # Correctly preserves original method visibility (public/protected/
  # private) after wrapping — define_method inside class_eval defaults
  # to whatever visibility is currently in effect, so this explicitly
  # restores whichever one the original method actually had.
  def self.hook_method(klass, method_name)
    is_public = klass.public_method_defined?(method_name)
    is_protected = klass.protected_method_defined?(method_name)
    is_private = klass.private_method_defined?(method_name)
    return false unless is_public || is_protected || is_private

    aliased = :"sprint42_orig_#{method_name}"
    already_hooked = klass.public_method_defined?(aliased) ||
                      klass.protected_method_defined?(aliased) ||
                      klass.private_method_defined?(aliased)
    return true if already_hooked

    klass.class_eval do
      alias_method aliased, method_name
      define_method(method_name) do |*args, &block|
        Sprint42Diag.before_call(self, method_name, args)
        result = send(aliased, *args, &block)
        Sprint42Diag.after_call(self, method_name, result)
        result
      end
      private method_name if is_private
      protected method_name if is_protected
    end

    visibility = is_private ? "private" : (is_protected ? "protected" : "public")
    log("hooked #{klass}##{method_name} (was #{visibility})")
    true
  end

  def self.before_call(obj, method_name, args)
    input_dir = safe_call(Input, :dir4)
    frame = safe_call(Graphics, :frame_count)
    dense = input_dir != 0 && input_dir != "N/A"

    if !dense
      return if frame.is_a?(Integer) && frame - @last_idle_log_frame < IDLE_LOG_THROTTLE_FRAMES
      @last_idle_log_frame = frame if frame.is_a?(Integer)
    end

    x_before = is_character_like?(obj) ? safe_call(obj, :x) : "N/A"
    y_before = is_character_like?(obj) ? safe_call(obj, :y) : "N/A"
    dir_before = is_character_like?(obj) ? safe_call(obj, :direction) : "N/A"

    log("CALL #{obj.class}##{method_name} args=#{args.inspect} frame=#{frame} Input.dir4=#{input_dir}(#{dir_name(input_dir)}) " \
        "x_before=#{x_before} y_before=#{y_before} direction_before=#{dir_before}")

    # Stash for after_call to read back — per-object, keyed by method
    # name, so nested/re-entrant calls to different methods don't
    # clobber each other.
    @pending ||= {}
    @pending[[obj.object_id, method_name]] = dense
  end

  def self.after_call(obj, method_name, result)
    @pending ||= {}
    was_dense = @pending.delete([obj.object_id, method_name])
    return if was_dense.nil? # before_call decided not to log this one (throttled idle case)

    x_after = is_character_like?(obj) ? safe_call(obj, :x) : "N/A"
    y_after = is_character_like?(obj) ? safe_call(obj, :y) : "N/A"
    dir_after = is_character_like?(obj) ? safe_call(obj, :direction) : "N/A"

    log("RETURN #{obj.class}##{method_name} result=#{result.inspect} x_after=#{x_after} y_after=#{y_after} direction_after=#{dir_after}")
  end

  # ---- Game_Player: discovery dump + candidate hooks ----

  def self.try_install_game_player
    return if @game_player_installed
    return unless defined?(Game_Player)
    @game_player_installed = true

    log("Game_Player now defined (frame=#{Graphics.frame_count}) — running method discovery.")

    begin
      all_methods = Game_Player.instance_methods(true)
      candidates = all_methods.select do |m|
        name = m.to_s
        CANDIDATE_NAME_PATTERNS.any? { |pattern| name =~ pattern }
      end.uniq.sort

      log("discovery: #{candidates.size} candidate method(s) matching /move|input|update|pass|walk|direction|player/ on Game_Player (including inherited):")
      candidates.each do |m|
        owner = begin
          Game_Player.instance_method(m).owner
        rescue => e
          "ERROR(#{e.class})"
        end
        log("  candidate: #{m} owner=#{owner}")
      end
    rescue => e
      log("discovery FAILED: #{e.class}: #{e.message}")
    end

    has_move_by_input = Game_Player.method_defined?(:move_by_input)
    log(has_move_by_input ? "move_by_input exists on Game_Player." : "move_by_input does NOT exist on Game_Player under this name (already known from Sprint 41).")

    hooked_count = 0
    CANDIDATE_HOOK_METHODS.each do |m|
      if hook_method(Game_Player, m)
        hooked_count += 1
      else
        log("Game_Player##{m} — missing, not hooked.")
      end
    end
    log("Game_Player hook pass complete — #{hooked_count}/#{CANDIDATE_HOOK_METHODS.size} candidate methods found and hooked.")
  end

  # ---- Scene_Map#update ----

  def self.try_install_scene_map
    return if @scene_map_installed
    return unless defined?(Scene_Map)
    @scene_map_installed = true

    log("Scene_Map now defined (frame=#{Graphics.frame_count}) — hooking #update.")

    unless Scene_Map.method_defined?(:update)
      log("Scene_Map#update — missing, not hooked.")
      return
    end

    Scene_Map.class_eval do
      unless method_defined?(:sprint42_orig_update)
        alias_method :sprint42_orig_update, :update
        define_method(:update) do
          Sprint42Diag.log_scene_map_tick
          sprint42_orig_update
        end
      end
    end
    log("Scene_Map#update hooked.")
  end

  def self.log_scene_map_tick
    input_dir = safe_call(Input, :dir4)
    frame = safe_call(Graphics, :frame_count)
    dense = input_dir != 0 && input_dir != "N/A"
    if !dense
      return if frame.is_a?(Integer) && frame - (@last_scene_map_idle_frame ||= 0) < IDLE_LOG_THROTTLE_FRAMES
      @last_scene_map_idle_frame = frame if frame.is_a?(Integer)
    end
    game_player_exists = defined?(${'$'}game_player) && !${'$'}game_player.nil?
    log("Scene_Map#update running frame=#{frame} Input.dir4=#{input_dir}(#{dir_name(input_dir)}) ${'$'}game_player_exists=#{game_player_exists}")
  end

  # ---- Game_Map#update ----

  def self.try_install_game_map
    return if @game_map_installed
    return unless defined?(Game_Map)
    @game_map_installed = true

    log("Game_Map now defined (frame=#{Graphics.frame_count}) — hooking #update.")

    unless Game_Map.method_defined?(:update)
      log("Game_Map#update — missing, not hooked.")
      return
    end

    Game_Map.class_eval do
      unless method_defined?(:sprint42_orig_update)
        alias_method :sprint42_orig_update, :update
        define_method(:update) do |*args|
          Sprint42Diag.log_game_map_tick
          sprint42_orig_update(*args)
        end
      end
    end
    log("Game_Map#update hooked.")
  end

  def self.log_game_map_tick
    input_dir = safe_call(Input, :dir4)
    frame = safe_call(Graphics, :frame_count)
    dense = input_dir != 0 && input_dir != "N/A"
    if !dense
      return if frame.is_a?(Integer) && frame - (@last_game_map_idle_frame ||= 0) < IDLE_LOG_THROTTLE_FRAMES
      @last_game_map_idle_frame = frame if frame.is_a?(Integer)
    end
    log("Game_Map#update running frame=#{frame} Input.dir4=#{input_dir}(#{dir_name(input_dir)})")
  end
end

class << Graphics
  unless method_defined?(:sprint42_orig_update)
    alias_method :sprint42_orig_update, :update
    define_method(:update) do
      Sprint42Diag.watcher_tick
      sprint42_orig_update
    end
  end
end

Sprint42Diag.log("diagnostic preload script loaded — file logger initialized (Sprint 42: movement path discovery)")

        """.trimIndent()

        /** Sprint 43 — temporary Ruby diagnostic preload script for command-to-movement pipeline tracing. */
        private const val SPRINT43_DIAGNOSTIC_SCRIPT_FILE_NAME = "sprint43-command-pipeline-diagnostic.rb"

        /** Sprint 43 — embedded Ruby command pipeline diagnostic. Kotlin-dollar escaped during generation. */
        private val SPRINT43_DIAGNOSTIC_SCRIPT_CONTENT = """
# ============================================================
# Sprint 43 — TEMPORARY, DISPOSABLE Ruby diagnostic overlay.
# Traces Game_Player#update_command_new and the command-to-movement
# pipeline, per Sprint 42's own discovery that update_command_new
# exists (owner=Game_Player) but was never hooked or observed called.
# ============================================================
# THIS FILE MODIFIES NOTHING PERMANENT. Injected via the Runtime
# Config Overlay's existing preloadScript mechanism — never touches
# Scripts.rxdata or any other original game file. Disabling the
# sprint43-command-pipeline-diagnostic mitigation removes every trace
# of this instantly and completely. Every hook uses alias_method
# (never a wholesale redefinition), so original behavior is always
# fully preserved underneath the added logging.
#
# SPRINT 43 CONTEXT: Sprint 42's own file-backed diagnostic confirmed
# Game_Player#update_command was called once on Left input, returned
# 4, correctly changed direction 6->4, but did NOT move x/y — and
# discovery found Game_Player#update_command_new (owner=Game_Player)
# was never hooked or observed called. The naming strongly suggests
# Essentials v21.1 may route actual movement through this "_new"
# method instead of (or in addition to) the base update_command this
# project's own working assumption was built around. This script
# hooks the full candidate pipeline in one pass, dumps relevant
# instance variables on every dense-logged call, and dumps a one-time
# full instance-variable/ancestor snapshot at install time.
#
# WHY A "WATCHER": preloadScript runs BEFORE Scripts.rxdata loads, so
# none of the target classes exist yet at this point. Graphics.update
# (a native mkxp-z binding, always available) is wrapped as a safe
# watcher: every frame, it checks whether each target has become
# available, and installs hooks the moment it does.
# ============================================================

module Sprint43Diag
  TAG = "SPRINT43_DIAG"
  LOG_FILE_NAME = "sprint43_command_pipeline_runtime.log"

  @game_player_installed = false
  @game_character_installed = false
  @ivar_snapshot_done = false
  @watcher_started_frame = nil
  @last_heartbeat_frame = 0
  @last_idle_log_frame = 0
  @never_defined_warned = false

  HEARTBEAT_THROTTLE_FRAMES    = 120
  IDLE_LOG_THROTTLE_FRAMES     = 300
  NEVER_DEFINED_TIMEOUT_FRAMES = 1800

  DIRECTION_NAMES = { 2 => "Down", 4 => "Left", 6 => "Right", 8 => "Up", 0 => "None" }

  RELEVANT_IVAR_PATTERNS = [/move/, /command/, /direction/, /through/, /stop/, /step/, /route/, /type/]

  GAME_PLAYER_HOOK_METHODS = [
    :update_command_new, :set_movement_type, :move_generic, :passable?,
    :update_command, :update, :update_stop
  ]

  GAME_CHARACTER_HOOK_METHODS = [:can_move_in_direction?, :can_move_from_coordinate?]

  OWNER_LOOKUP_METHODS = [
    :update, :update_command, :update_command_new, :update_stop,
    :move_generic, :passable?, :set_movement_type,
    :can_move_in_direction?, :can_move_from_coordinate?
  ]

  def self.log(message)
    full_message = "#{TAG}: #{message}"
    begin
      File.open(LOG_FILE_NAME, "a") { |f| f.puts(full_message) }
    rescue => e
      # Never let a file-write failure crash the game.
    end
    begin
      puts full_message
    rescue => e
      # Secondary output only.
    end
  end

  def self.safe_call(obj, method_name, *args)
    return "N/A" if obj.nil?
    return "N/A" unless obj.respond_to?(method_name)
    obj.send(method_name, *args)
  rescue => e
    "ERROR(#{e.class}: #{e.message})"
  end

  def self.dir_name(d)
    DIRECTION_NAMES[d] || "Unknown(#{d})"
  end

  def self.is_character_like?(obj)
    !obj.nil? && obj.respond_to?(:x) && obj.respond_to?(:y) && obj.respond_to?(:direction)
  end

  def self.relevant_ivars(obj)
    return "N/A" if obj.nil?
    names = obj.instance_variables.select { |iv| RELEVANT_IVAR_PATTERNS.any? { |p| iv.to_s =~ p } }
    return "(none matched)" if names.empty?
    names.map do |iv|
      value = begin
        obj.instance_variable_get(iv).inspect
      rescue => e
        "ERROR(#{e.class})"
      end
      "#{iv}=#{value}"
    end.join(", ")
  rescue => e
    "ERROR(#{e.class}: #{e.message})"
  end

  def self.watcher_tick
    @watcher_started_frame ||= Graphics.frame_count
    frames_waited = Graphics.frame_count - @watcher_started_frame

    all_installed = @game_player_installed && @game_character_installed && @ivar_snapshot_done
    unless all_installed
      if Graphics.frame_count - @last_heartbeat_frame >= HEARTBEAT_THROTTLE_FRAMES
        log("watcher alive (frame=#{Graphics.frame_count}, waited=#{frames_waited}) — " \
            "Game_Player installed=#{@game_player_installed} Game_Character installed=#{@game_character_installed} ivar_snapshot_done=#{@ivar_snapshot_done}")
        @last_heartbeat_frame = Graphics.frame_count
      end

      if !@never_defined_warned && frames_waited >= NEVER_DEFINED_TIMEOUT_FRAMES
        log("WARNING — not all targets ready after #{frames_waited} frames. " \
            "Game_Player=#{defined?(Game_Player).nil? ? 'undefined' : 'defined'} " \
            "Game_Character=#{defined?(Game_Character).nil? ? 'undefined' : 'defined'} " \
            "${'$'}game_player=#{(defined?(${'$'}game_player) && !${'$'}game_player.nil?) ? 'present' : 'absent'}")
        @never_defined_warned = true
      end
    end

    try_install_game_player
    try_install_game_character
    try_snapshot_ivars_and_ancestors
  end

  def self.hook_method(klass, method_name)
    is_public = klass.public_method_defined?(method_name)
    is_protected = klass.protected_method_defined?(method_name)
    is_private = klass.private_method_defined?(method_name)
    return false unless is_public || is_protected || is_private

    aliased = :"sprint43_orig_#{method_name}"
    already_hooked = klass.public_method_defined?(aliased) ||
                      klass.protected_method_defined?(aliased) ||
                      klass.private_method_defined?(aliased)
    return true if already_hooked

    klass.class_eval do
      alias_method aliased, method_name
      define_method(method_name) do |*args, &block|
        Sprint43Diag.before_call(self, method_name, args)
        result = send(aliased, *args, &block)
        Sprint43Diag.after_call(self, method_name, result)
        result
      end
      private method_name if is_private
      protected method_name if is_protected
    end

    visibility = is_private ? "private" : (is_protected ? "protected" : "public")
    log("hooked #{klass}##{method_name} (was #{visibility})")
    true
  end

  def self.before_call(obj, method_name, args)
    dense = begin
      input_dir_now = safe_call(Input, :dir4)
      is_dir_active = input_dir_now != 0 && input_dir_now != "N/A"
      is_dir_active
    rescue => e
      false
    end

    frame = safe_call(Graphics, :frame_count)
    input_dir = safe_call(Input, :dir4)

    if !dense
      return if frame.is_a?(Integer) && frame - @last_idle_log_frame < IDLE_LOG_THROTTLE_FRAMES
      @last_idle_log_frame = frame if frame.is_a?(Integer)
    end

    x_before = is_character_like?(obj) ? safe_call(obj, :x) : "N/A"
    y_before = is_character_like?(obj) ? safe_call(obj, :y) : "N/A"
    dir_before = is_character_like?(obj) ? safe_call(obj, :direction) : "N/A"
    ivars = is_character_like?(obj) ? relevant_ivars(obj) : "N/A"

    log("CALL #{obj.class}##{method_name} args=#{args.inspect} frame=#{frame} Input.dir4=#{input_dir}(#{dir_name(input_dir)}) " \
        "x_before=#{x_before} y_before=#{y_before} direction_before=#{dir_before} ivars_before=[#{ivars}]")

    @pending ||= {}
    @pending[[obj.object_id, method_name]] = dense
  end

  def self.after_call(obj, method_name, result)
    @pending ||= {}
    was_dense = @pending.delete([obj.object_id, method_name])
    return if was_dense.nil?

    x_after = is_character_like?(obj) ? safe_call(obj, :x) : "N/A"
    y_after = is_character_like?(obj) ? safe_call(obj, :y) : "N/A"
    dir_after = is_character_like?(obj) ? safe_call(obj, :direction) : "N/A"
    ivars = is_character_like?(obj) ? relevant_ivars(obj) : "N/A"

    log("RETURN #{obj.class}##{method_name} result=#{result.inspect} x_after=#{x_after} y_after=#{y_after} direction_after=#{dir_after} ivars_after=[#{ivars}]")
  end

  def self.try_install_game_player
    return if @game_player_installed
    return unless defined?(Game_Player)
    @game_player_installed = true

    log("Game_Player now defined (frame=#{Graphics.frame_count}).")

    OWNER_LOOKUP_METHODS.each do |m|
      owner = begin
        exists = Game_Player.method_defined?(m) || Game_Player.private_method_defined?(m)
        exists ? Game_Player.instance_method(m).owner : "N/A (method does not exist)"
      rescue => e
        "ERROR(#{e.class})"
      end
      log("owner lookup: Game_Player##{m} owner=#{owner}")
    end

    hooked_count = 0
    GAME_PLAYER_HOOK_METHODS.each do |m|
      if hook_method(Game_Player, m)
        hooked_count += 1
      else
        log("Game_Player##{m} — missing, not hooked.")
      end
    end
    log("Game_Player hook pass complete — #{hooked_count}/#{GAME_PLAYER_HOOK_METHODS.size} candidate methods found and hooked.")
  end

  def self.try_install_game_character
    return if @game_character_installed
    return unless defined?(Game_Character)
    @game_character_installed = true

    log("Game_Character now defined (frame=#{Graphics.frame_count}).")

    hooked_count = 0
    GAME_CHARACTER_HOOK_METHODS.each do |m|
      if hook_method(Game_Character, m)
        hooked_count += 1
      else
        log("Game_Character##{m} — missing, not hooked.")
      end
    end

    if defined?(Game_Player) && Game_Player.method_defined?(:update_command)
      owner = begin
        Game_Player.instance_method(:update_command).owner
      rescue => e
        nil
      end
      if owner == Game_Character
        if hook_method(Game_Character, :update_command)
          hooked_count += 1
          log("Game_Character#update_command hooked separately — Game_Player inherits this implementation directly (owner=Game_Character).")
        end
      else
        log("Game_Character#update_command NOT separately hooked — Game_Player's own update_command has a different owner (#{owner.inspect}), already covered by the Game_Player hook pass.")
      end
    end

    log("Game_Character hook pass complete — #{hooked_count}/#{GAME_CHARACTER_HOOK_METHODS.size + 1} candidate methods found and hooked.")
  end

  def self.try_snapshot_ivars_and_ancestors
    return if @ivar_snapshot_done
    return unless defined?(Game_Player)
    return unless defined?(${'$'}game_player) && !${'$'}game_player.nil?
    @ivar_snapshot_done = true

    log("Game_Player.ancestors=#{Game_Player.ancestors.inspect}")

    begin
      all_ivars = ${'$'}game_player.instance_variables
      log("${'$'}game_player full instance_variables (#{all_ivars.size} total, unfiltered):")
      all_ivars.each do |iv|
        value = begin
          ${'$'}game_player.instance_variable_get(iv).inspect
        rescue => e
          "ERROR(#{e.class})"
        end
        log("  #{iv} = #{value}")
      end
    rescue => e
      log("ivar snapshot FAILED: #{e.class}: #{e.message}")
    end
  end
end

class << Graphics
  unless method_defined?(:sprint43_orig_update)
    alias_method :sprint43_orig_update, :update
    define_method(:update) do
      Sprint43Diag.watcher_tick
      sprint43_orig_update
    end
  end
end

Sprint43Diag.log("diagnostic preload script loaded — file logger initialized (Sprint 43: update_command_new and command pipeline trace)")

        """.trimIndent()

        /** Sprint 44 — temporary Ruby diagnostic preload script for timebase/FPS/delta tracing. */
        private const val SPRINT44_DIAGNOSTIC_SCRIPT_FILE_NAME = "sprint44-timebase-diagnostic.rb"

        /** Sprint 48 — disposable System.uptime/System.delta seconds shim. */
        private const val SPRINT48_SHIM_SCRIPT_FILE_NAME = "sprint48-system-uptime-seconds-shim.rb"
        private const val SPRINT50_SHIM_SCRIPT_FILE_NAME = "sprint50-speed-control-shim.rb"
        private val SPRINT50_SHIM_SCRIPT_CONTENT = """
# ============================================================
# Sprint 50 — TEMPORARY, DISPOSABLE Ruby compatibility shim.
# Adds a live-adjustable game-speed multiplier (x1/x2/x3) layered on
# top of Sprint 48's own System.uptime/System.delta seconds shim,
# using an ANCHORED MONOTONIC TIME SCALING model.
# ============================================================
# THIS FILE MODIFIES NOTHING PERMANENT. Injected via the Runtime
# Config Overlay's existing preloadScript mechanism — never touches
# Scripts.rxdata or any other original game file. Disabling the
# sprint50-speed-control mitigation removes every trace of this
# instantly and completely.
#
# A REAL BUG CAUGHT ON REVIEW, BEFORE BUILDING — AND WHY THE FIX
# MATTERS: an earlier draft of this shim computed
# `shimmed_uptime = raw_seconds * current_speed` directly. This is
# broken for a LIVE speed change: System.uptime is an ABSOLUTE
# elapsed-since-startup value, not a per-frame delta — multiplying
# the absolute value by a NEW speed factor at the moment of a switch
# produces an instant discontinuity. Concretely: if the game has run
# for 60 real seconds at x1 (uptime reads ~60), and the player then
# selects x2, the very next frame would jump from ~60 to ~120 in a
# single tick — exactly the class of bug this whole investigation
# (Sprint 44-48) spent many sprints diagnosing and fixing for the
# microsecond/seconds mismatch. A movement/timer system that reads
# "0.25 seconds have passed" as "60 seconds have passed" behaves
# exactly as badly as the original bug this project fixed, just
# triggered by a menu tap instead of a unit error.
#
# THE FIX — ANCHORED MONOTONIC TIME SCALING: rather than scaling the
# absolute clock, this shim tracks a "scaled timeline" that is
# PIECEWISE LINEAR — it grows at whatever the CURRENT speed is, but
# is re-anchored (not reset) every time the speed changes, so the
# scaled clock's own value is always CONTINUOUS across a speed
# change, in either direction:
#
#   scaled_time = base_scaled_seconds + ((raw_seconds_now - base_raw_seconds) * current_speed)
#
# On every speed change:
#   1. Compute scaled_now using the OLD speed (the formula above,
#      before current_speed is updated).
#   2. base_scaled_seconds = scaled_now   (anchor the new segment's
#      own starting point to exactly where the old segment left off)
#   3. base_raw_seconds = raw_seconds_now (reset the raw-time anchor
#      to "now")
#   4. current_speed = new_speed
#
# At the exact instant of a switch, raw_seconds_now == base_raw_seconds,
# so scaled_time == base_scaled_seconds exactly — no jump, in either
# direction — and the scaled clock then grows at the new rate going
# forward. This is the same standard technique used for variable-
# speed media/simulation clocks generally, not something invented ad
# hoc for this shim.
#
# WHY x1 IS BYTE-IDENTICAL TO THE SPRINT 48 BASELINE: base_scaled_
# seconds is initialized to the REAL raw time at install (not zero).
# At x1 with no speed change ever having occurred, the formula
# algebraically reduces to exactly raw_seconds_now — the same value
# Sprint 48 alone would report at any given moment. Verified, not
# just reasoned about — see this sprint's own delivery notes for the
# real Ruby execution trace confirming this.
#
# COMPOSABILITY WITH SPRINT 48 (defensive, works either order): if
# Sprint 48's own shim has already installed (detected via
# `System.respond_to?(:sprint48_orig_uptime)`), "raw seconds" is read
# directly from the already-seconds-scale System.uptime it provides.
# If Sprint 48's own shim is absent, this shim does the microsecond-
# to-seconds conversion itself as a fallback before applying the
# anchored scaling formula on top.
#
# LIVE SWITCHING WITHOUT A RELAUNCH: the current speed multiplier is
# read from a small file (SPEED_SCALE_FILE_NAME) in the game's own
# current working directory — the Android-side Runtime Quick Menu
# writes "1"/"2"/"3" to this file when the player taps a speed
# button; this shim polls it on a throttled schedule (every
# POLL_THROTTLE_FRAMES) via the same Graphics.update watcher pattern
# every prior sprint's own scripts have used.
# ============================================================

module Sprint50Diag
  TAG = "SPRINT50_DIAG"
  LOG_FILE_NAME = "sprint50_speed_control_runtime.log"
  SPEED_SCALE_FILE_NAME = "sprint50_speed_scale.txt"

  MICROSECONDS_PER_SECOND = 1_000_000.0
  VALID_SPEEDS = [1, 2, 3].freeze
  DEFAULT_SPEED = 1

  @installed = false
  @sprint48_detected = false
  @current_speed = DEFAULT_SPEED
  @base_scaled_seconds = 0.0
  @base_raw_seconds = 0.0
  @last_poll_frame = 0

  POLL_THROTTLE_FRAMES = 15 # a quarter second at 60fps — near-instant response to a menu tap without polling every single frame

  def self.log(message)
    full_message = "#{TAG}: #{message}"
    begin
      File.open(LOG_FILE_NAME, "a") { |f| f.puts(full_message) }
    rescue => e
      # Never let a file-write failure crash the game.
    end
    begin
      puts full_message
    rescue => e
      # Secondary output only.
    end
  end

  def self.read_speed_from_file
    return DEFAULT_SPEED unless File.exist?(SPEED_SCALE_FILE_NAME)
    raw = File.read(SPEED_SCALE_FILE_NAME).strip
    value = raw.to_i
    VALID_SPEEDS.include?(value) ? value : DEFAULT_SPEED
  rescue => e
    DEFAULT_SPEED
  end

  # The un-scaled "raw seconds" reading — either Sprint 48's own
  # already-seconds-scale uptime, or this shim's own microsecond-to-
  # seconds fallback conversion, depending on what was detected at
  # install time. This is the input to the anchored scaling formula
  # below, never returned directly to Essentials.
  def self.raw_seconds_now
    raw = System.sprint50_orig_uptime
    @sprint48_detected ? raw : (raw / MICROSECONDS_PER_SECOND)
  end

  # The anchored, piecewise-linear scaled clock — this IS what
  # System.uptime/System.delta return to Essentials once installed.
  def self.scaled_uptime
    @base_scaled_seconds + ((raw_seconds_now - @base_raw_seconds) * @current_speed)
  end

  def self.current_speed
    @current_speed
  end

  # Re-anchors the scaled timeline at the current instant, per the
  # 4-step model in this file's own header comment — the entire fix
  # for the time-jump bug lives here.
  def self.change_speed(new_speed)
    return if new_speed == @current_speed
    now_scaled = scaled_uptime  # step 1 — computed using the OLD current_speed, before it changes
    now_raw = raw_seconds_now
    old_speed = @current_speed

    @base_scaled_seconds = now_scaled  # step 2
    @base_raw_seconds = now_raw        # step 3
    @current_speed = new_speed         # step 4

    log("speed changed: x#{old_speed} -> x#{new_speed} " \
        "(anchor: scaled=#{now_scaled.round(4)} raw=#{now_raw.round(4)} — continuous, no jump)")
  end

  def self.poll_speed
    return if Graphics.frame_count - @last_poll_frame < POLL_THROTTLE_FRAMES
    @last_poll_frame = Graphics.frame_count

    new_speed = read_speed_from_file
    change_speed(new_speed)
  end

  def self.install
    return if @installed
    @installed = true

    unless defined?(System) && System.respond_to?(:uptime) && System.respond_to?(:delta)
      log("FAILED TO INSTALL — System.uptime/System.delta not available at preload time.")
      return
    end

    sprint48_already_applied = System.respond_to?(:sprint48_orig_uptime)

    # Step 1 of installation — alias the originals only. The wrapped
    # #uptime/#delta methods themselves are defined further below,
    # AFTER the anchor is correctly initialized (see the ordering
    # note right after this block).
    System.singleton_class.instance_eval do
      unless method_defined?(:sprint50_orig_uptime)
        alias_method :sprint50_orig_uptime, :uptime
      end
      unless method_defined?(:sprint50_orig_delta)
        alias_method :sprint50_orig_delta, :delta
      end
    end

    @sprint48_detected = sprint48_already_applied

    # Critical ordering: the anchor must be initialized to the REAL
    # current raw time (via raw_seconds_now, which needs
    # sprint50_orig_uptime and @sprint48_detected to already be set,
    # both true at this point) — and base_scaled_seconds is set to
    # that SAME value (not zero), so that at x1 with no speed change
    # yet, scaled_uptime algebraically equals raw_seconds_now exactly,
    # matching the Sprint 48 baseline byte-for-byte.
    initial_raw = raw_seconds_now
    @base_raw_seconds = initial_raw
    @base_scaled_seconds = initial_raw

    # Step 2 of installation — now define the actual wrapped
    # accessors, using the fully-initialized anchor state above.
    System.singleton_class.instance_eval do
      define_method(:uptime) { Sprint50Diag.scaled_uptime }
      define_method(:delta) { Sprint50Diag.scaled_uptime }
    end

    log("System.uptime/System.delta speed-control shim installed (anchored scaling model) — " \
        "sprint48_shim_detected=#{sprint48_already_applied} default_speed=x#{DEFAULT_SPEED} " \
        "initial_raw_seconds=#{initial_raw.round(4)} " \
        "(x1 is byte-identical to the Sprint 48 baseline from this point forward).")
  end
end

Sprint50Diag.install

class << Graphics
  # Safe to use the plain keyword form here — this block only calls
  # ordinary module methods (Sprint50Diag.poll_speed,
  # sprint50_orig_update), not a captured local variable from an
  # enclosing method, so the class-reopening scope barrier that
  # required System.singleton_class.instance_eval above does not
  # apply to this particular block.
  unless method_defined?(:sprint50_orig_update)
    alias_method :sprint50_orig_update, :update
    define_method(:update) do
      Sprint50Diag.poll_speed
      sprint50_orig_update
    end
  end
end

        """.trimIndent()


        /** Sprint 48 — embedded Ruby shim content. */
        private val SPRINT48_SHIM_SCRIPT_CONTENT = """
# ============================================================
# Sprint 48 — TEMPORARY, DISPOSABLE Ruby compatibility shim.
# Wraps System.uptime / System.delta so Essentials v21.1 receives
# SECONDS instead of raw microseconds — fixing the confirmed root
# cause (see Investigation-Closure-Root-Cause-Confirmed.md) of both
# the D-Pad movement gate and the playtime runaway.
# ============================================================
# THIS FILE MODIFIES NOTHING PERMANENT. Injected via the Runtime
# Config Overlay's existing preloadScript mechanism — never touches
# Scripts.rxdata or any other original game file. Disabling the
# sprint48-system-uptime-seconds-shim mitigation removes every trace
# of this instantly and completely — Essentials would simply see raw
# microseconds again, exactly as before this shim existed.
#
# ROOT CAUSE THIS FIXES (confirmed via direct read of Essentials
# v21.1's own real script source this investigation pass):
#   - mkxp-z's own native System.uptime/System.delta (both aliases
#     for the same C function, mkxpDelta -> SharedState::runTime())
#     return elapsed MICROSECONDS since engine startup, as a plain
#     integer.
#   - Essentials v21.1's own Game_Character#update computes
#     `@delta_t = time_now - @last_update_time` from System.uptime,
#     then checks `return if @delta_t > 0.25` — a check clearly
#     intended for SECONDS (per its own comment, "Was in a menu;
#     delay movement"), but fed a microsecond-scale value instead,
#     so it fires almost every frame and blocks update_command.
#   - Game_Player#update_command_new independently checks
#     `System.uptime - @lastdirframe >= 0.075` (again intended as
#     seconds) to gate continuous movement on a held direction — the
#     same unit mismatch blocks this too.
#
# WHY NO "WATCHER" IS NEEDED HERE (unlike Game_Player/Scene_Map/
# Game_System in Sprints 41-47): System is a native mkxp-z binding,
# available immediately once preloadScript itself starts running —
# the same category as Graphics/Input, which every prior sprint's own
# Graphics.update hook has already relied on being available at this
# exact point without any deferred-install logic. This shim installs
# immediately, unconditionally, at the top level of this file.
# ============================================================

module Sprint48Diag
  TAG = "SPRINT48_DIAG"

  MICROSECONDS_PER_SECOND = 1_000_000.0

  @installed = false
  @last_heartbeat_frame = 0
  HEARTBEAT_THROTTLE_FRAMES = 120 # ~2 real seconds at 60fps

  def self.log(message)
    puts "#{TAG}: #{message}"
  end

  # Installs the shim. Idempotent — safe even if this file is somehow
  # loaded more than once in a session (checks method_defined? before
  # aliasing, exactly the same defensive pattern every prior sprint's
  # own hooks have used).
  def self.install
    return if @installed
    @installed = true

    unless defined?(System) && System.respond_to?(:uptime) && System.respond_to?(:delta)
      log("FAILED TO INSTALL — System.uptime/System.delta not available at preload time (unexpected; every prior sprint's own evidence shows native bindings like this are always available this early). Essentials will receive raw, unshimmed values.")
      return
    end

    class << System
      unless method_defined?(:sprint48_orig_uptime)
        alias_method :sprint48_orig_uptime, :uptime
        # Deliberately just a division — this method is called very
        # frequently (once per Game_Character per frame, across every
        # NPC/event on a map, plus twice more per frame from
        # Game_Player#update_command_new) — kept as cheap as possible,
        # with all logging moved to the separate, throttled heartbeat
        # below rather than living in this hot path.
        define_method(:uptime) { sprint48_orig_uptime / Sprint48Diag::MICROSECONDS_PER_SECOND }
      end

      unless method_defined?(:sprint48_orig_delta)
        alias_method :sprint48_orig_delta, :delta
        define_method(:delta) { sprint48_orig_delta / Sprint48Diag::MICROSECONDS_PER_SECOND }
      end
    end

    raw_sample = System.sprint48_orig_uptime
    shimmed_sample = System.uptime
    log("System.uptime/System.delta shim installed — original values preserved as " \
        "System.sprint48_orig_uptime/System.sprint48_orig_delta. " \
        "Sample: raw=#{raw_sample} (microseconds) shimmed=#{shimmed_sample} (seconds) " \
        "— formula: raw / 1_000_000.0.")
  end

  # Lightweight, throttled confirmation that shimmed values remain
  # sane over time — deliberately decoupled from the wrapped uptime/
  # delta methods themselves (which must stay on the hot path and add
  # no logging overhead of their own).
  def self.heartbeat_tick
    return unless @installed
    return unless defined?(Graphics) && Graphics.respond_to?(:frame_count)

    frame = Graphics.frame_count
    return if frame - @last_heartbeat_frame < HEARTBEAT_THROTTLE_FRAMES
    @last_heartbeat_frame = frame

    raw = System.respond_to?(:sprint48_orig_uptime) ? System.sprint48_orig_uptime : "N/A"
    shimmed = System.uptime
    log("heartbeat (frame=#{frame}) — System.uptime raw=#{raw} shimmed=#{shimmed} (seconds-scale, sane if this looks like ordinary elapsed session time, not a huge number)")
  end
end

Sprint48Diag.install

class << Graphics
  unless method_defined?(:sprint48_orig_update)
    alias_method :sprint48_orig_update, :update
    define_method(:update) do
      Sprint48Diag.heartbeat_tick
      sprint48_orig_update
    end
  end
end

        """.trimIndent()

        /** Sprint 44 — embedded Ruby timebase diagnostic. Kotlin-dollar escaped during generation. */
        private val SPRINT44_DIAGNOSTIC_SCRIPT_CONTENT = """
# ============================================================
# Sprint 44 — TEMPORARY, DISPOSABLE Ruby diagnostic overlay.
# Traces timebase/FPS state around Game_Player#update_command_new,
# per Sprint 43's own discovery of abnormal timing ivars:
#   @lastdirframe = 26870754, @last_update_time = 37207134,
#   @delta_t = 19485 (plus save/play time showing 45k+ hours).
# ============================================================
# THIS FILE MODIFIES NOTHING PERMANENT. Injected via the Runtime
# Config Overlay's existing preloadScript mechanism — never touches
# Scripts.rxdata or any other original game file. Disabling the
# sprint44-timebase-diagnostic mitigation removes every trace of this
# instantly and completely. The one hook here uses alias_method
# (never a wholesale redefinition), so original behavior is always
# fully preserved underneath the added logging.
#
# SPRINT 44 CONTEXT: those Sprint 43 ivar values are absurd for a
# session that just started — if @delta_t is milliseconds, 19485 is
# ~19.5 REAL SECONDS reported for a single frame (should be ~16ms at
# 60fps); if @lastdirframe is a frame count, 26870754 frames is over
# 120 days of continuous 60fps runtime. This script cross-references
# these Essentials-internal ivars against independently-read native/
# Ruby timing sources (Graphics.frame_count, Graphics.frame_rate,
# Time.now, Process.clock_gettime) every dense input frame, to
# determine whether the runtime's own notion of elapsed time is
# genuinely running away, or whether these specific ivars are stale/
# corrupted independent of otherwise-normal engine timing.
#
# WHY A "WATCHER": preloadScript runs BEFORE Scripts.rxdata loads, so
# Game_Player does not exist yet at this point. Graphics.update (a
# native mkxp-z binding, always available) is wrapped as a safe
# watcher: every frame, it checks whether Game_Player has become
# defined yet, and installs the hook the moment it does.
# ============================================================

module Sprint44Diag
  TAG = "SPRINT44_DIAG"
  LOG_FILE_NAME = "sprint44_timebase_runtime.log"

  @installed = false
  @watcher_started_frame = nil
  @last_heartbeat_frame = 0
  @last_idle_log_frame = 0
  @never_defined_warned = false
  @native_timing_sources_logged = false

  # Sprint 44 update — delta-tracking across consecutive dense calls,
  # per Ti's own new evidence: a CLEAN new save still shows ~8000
  # hours of playtime immediately, and @lastdirframe/@lastdir DO reset
  # sanely to 0 on a clean save — together, this rules out corrupted
  # save data as the primary cause and points at the runtime's own
  # timebase itself. Tracking the frame_count/wall-clock delta BETWEEN
  # consecutive "before update_command_new" calls (not within a single
  # call's own before/after pair, which is always ~0 frames apart and
  # wouldn't reveal a runaway rate) makes an abnormal implied FPS
  # directly, immediately visible in the log — no manual line-diffing
  # required.
  @prev_dense_frame_count = nil
  @prev_dense_monotonic_time = nil

  HEARTBEAT_THROTTLE_FRAMES    = 120
  IDLE_LOG_THROTTLE_FRAMES     = 300
  NEVER_DEFINED_TIMEOUT_FRAMES = 1800

  DIRECTION_NAMES = { 2 => "Down", 4 => "Left", 6 => "Right", 8 => "Up", 0 => "None" }

  # The exact 6 timing ivars named in this sprint's own requirements.
  TIMING_IVAR_NAMES = [
    :@lastdirframe, :@last_update_time, :@delta_t,
    :@move_time, :@command_delay, :@stop_count
  ]

  def self.log(message)
    full_message = "#{TAG}: #{message}"
    begin
      File.open(LOG_FILE_NAME, "a") { |f| f.puts(full_message) }
    rescue => e
      # Never let a file-write failure crash the game.
    end
    begin
      puts full_message
    rescue => e
      # Secondary output only.
    end
  end

  def self.safe_call(obj, method_name, *args)
    return "N/A" if obj.nil?
    return "N/A" unless obj.respond_to?(method_name)
    obj.send(method_name, *args)
  rescue => e
    "ERROR(#{e.class}: #{e.message})"
  end

  def self.dir_name(d)
    DIRECTION_NAMES[d] || "Unknown(#{d})"
  end

  # ---- Native/Ruby timing sources — requirement 1 ----
  # Logged once at hook-install time (a static description of what's
  # available/what the values look like at that moment), then again
  # on every dense frame alongside the ivar snapshot, so their own
  # evolution over time can be directly compared against the
  # Game_Player ivars.
  def self.log_timing_sources(label, track_delta: false)
    frame_count = safe_call(Graphics, :frame_count)
    frame_rate = Graphics.respond_to?(:frame_rate) ? safe_call(Graphics, :frame_rate) : "N/A (Graphics.frame_rate not available)"
    time_now = begin
      Time.respond_to?(:now) ? Time.now.to_f : "N/A"
    rescue => e
      "ERROR(#{e.class})"
    end
    clock_gettime = begin
      if defined?(Process) && Process.respond_to?(:clock_gettime) && defined?(Process::CLOCK_MONOTONIC)
        Process.clock_gettime(Process::CLOCK_MONOTONIC)
      else
        "N/A (Process.clock_gettime/CLOCK_MONOTONIC not available)"
      end
    rescue => e
      "ERROR(#{e.class})"
    end

    log("[#{label}] native/Ruby timing — Graphics.frame_count=#{frame_count} Graphics.frame_rate=#{frame_rate} " \
        "Time.now=#{time_now} Process.clock_gettime(MONOTONIC)=#{clock_gettime}")

    return unless track_delta

    frame_count_numeric = frame_count.is_a?(Integer) ? frame_count : nil
    clock_numeric = clock_gettime.is_a?(Numeric) ? clock_gettime : nil

    if @prev_dense_frame_count && @prev_dense_monotonic_time && frame_count_numeric && clock_numeric
      frames_elapsed = frame_count_numeric - @prev_dense_frame_count
      real_seconds_elapsed = clock_numeric - @prev_dense_monotonic_time
      implied_fps = real_seconds_elapsed > 0 ? (frames_elapsed / real_seconds_elapsed).round(2) : "N/A (zero or negative real_seconds_elapsed)"
      log("[#{label}] DELTA since last dense call — frames_elapsed=#{frames_elapsed} real_seconds_elapsed=#{real_seconds_elapsed.round(4)} " \
          "implied_fps=#{implied_fps} (sane range: roughly 30-60; wildly outside this range directly supports a runtime timebase runaway)")
    else
      log("[#{label}] DELTA since last dense call — N/A (first dense call this session, or a required timing source was unavailable)")
    end

    @prev_dense_frame_count = frame_count_numeric if frame_count_numeric
    @prev_dense_monotonic_time = clock_numeric if clock_numeric
  end

  # ---- Game_Player timing ivars — requirement 2 ----
  def self.log_timing_ivars(player, label)
    values = TIMING_IVAR_NAMES.map do |iv|
      present = player.instance_variables.include?(iv)
      value = present ? (begin
        player.instance_variable_get(iv).inspect
      rescue => e
        "ERROR(#{e.class})"
      end) : "(not set)"
      "#{iv}=#{value}"
    end.join(" ")

    log("[#{label}] Game_Player timing ivars: #{values}")
  end

  def self.watcher_tick
    @watcher_started_frame ||= Graphics.frame_count
    return if @installed

    frames_waited = Graphics.frame_count - @watcher_started_frame

    if Graphics.frame_count - @last_heartbeat_frame >= HEARTBEAT_THROTTLE_FRAMES
      log("watcher alive (frame=#{Graphics.frame_count}, waited=#{frames_waited}) — Game_Player not yet defined.")
      @last_heartbeat_frame = Graphics.frame_count
    end

    if !@never_defined_warned && frames_waited >= NEVER_DEFINED_TIMEOUT_FRAMES
      log("WARNING — Game_Player still not defined after #{frames_waited} frames.")
      @never_defined_warned = true
    end

    try_install
  end

  def self.try_install
    return if @installed
    return unless defined?(Game_Player)
    @installed = true

    log("Game_Player now defined (frame=#{Graphics.frame_count}).")
    log_timing_sources("install-time")

    unless Game_Player.method_defined?(:update_command_new)
      log("Game_Player#update_command_new — missing, cannot hook. (Contradicts Sprint 43's own discovery — re-verify this build matches.)")
      return
    end

    Game_Player.class_eval do
      unless method_defined?(:sprint44_orig_update_command_new)
        alias_method :sprint44_orig_update_command_new, :update_command_new
        define_method(:update_command_new) do |*args, &block|
          Sprint44Diag.before_update_command_new(self, args)
          result = send(:sprint44_orig_update_command_new, *args, &block)
          Sprint44Diag.after_update_command_new(self, result)
          result
        end
      end
    end
    log("Game_Player#update_command_new hooked (timing-focused).")
  end

  def self.before_update_command_new(player, args)
    input_dir = safe_call(Input, :dir4)
    frame = safe_call(Graphics, :frame_count)
    dense = input_dir != 0 && input_dir != "N/A"

    if !dense
      return if frame.is_a?(Integer) && frame - @last_idle_log_frame < IDLE_LOG_THROTTLE_FRAMES
      @last_idle_log_frame = frame if frame.is_a?(Integer)
    end

    x_before = safe_call(player, :x)
    y_before = safe_call(player, :y)
    dir_before = safe_call(player, :direction)

    log("CALL update_command_new args=#{args.inspect} frame=#{frame} Input.dir4=#{input_dir}(#{dir_name(input_dir)}) x_before=#{x_before} y_before=#{y_before} direction_before=#{dir_before}")
    log_timing_sources("before update_command_new", track_delta: true)
    log_timing_ivars(player, "before update_command_new")

    @dense_pending = dense
  end

  def self.after_update_command_new(player, result)
    return unless @dense_pending
    @dense_pending = false

    x_after = safe_call(player, :x)
    y_after = safe_call(player, :y)
    dir_after = safe_call(player, :direction)

    log("RETURN update_command_new result=#{result.inspect} x_after=#{x_after} y_after=#{y_after} direction_after=#{dir_after}")
    log_timing_sources("after update_command_new")
    log_timing_ivars(player, "after update_command_new")
    log("--- finished update_command_new trace for this call ---")
  end
end

class << Graphics
  unless method_defined?(:sprint44_orig_update)
    alias_method :sprint44_orig_update, :update
    define_method(:update) do
      Sprint44Diag.watcher_tick
      sprint44_orig_update
    end
  end
end

Sprint44Diag.log("diagnostic preload script loaded — file logger initialized (Sprint 44: timebase/FPS movement gate trace)")

        """.trimIndent()

        private val WINDOW_TITLE_PATTERN = Regex("\"windowTitle\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

        /**
         * Sprint 26 hardening — captures the *value* of an existing
         * `preloadScript` key, whether it's a JSON array (`[...]`) or a
         * bare string (`"..."`), so [applyZlibPreloadMitigation] can
         * append to it rather than skip it entirely. Deliberately a
         * targeted regex for this one field's own value shape, not a
         * general JSON5 parser — matching the approved Sprint 26 scope's
         * own caution against overbuilding a parser.
         */
        private val PRELOAD_SCRIPT_VALUE_PATTERN = Regex("\"preloadScript\"\\s*:\\s*(\\[[^\\]]*\\]|\"[^\"]*\")")

        /** Sprint 45 — captures the existing value of "syncToRefreshrate", if present. */
        private val SYNC_TO_REFRESHRATE_PATTERN = Regex("\\\"syncToRefreshrate\\\"\\s*:\\s*(true|false)")

        /** Sprint 45 — captures the existing value of "fixedFramerate", if present. */
        private val FIXED_FRAMERATE_ANY_OCCURRENCE_PATTERN = Regex("\\\"fixedFramerate\\\"\\s*:\\s*(-?\\d+)")

        /**
         * Sprint 30 — captures the value of an existing
         * `fixedAspectRatio` key, if present, so
         * [applyAspectFitRenderMitigation] can replace it (if `false`)
         * or skip cleanly (if already `true`), rather than blindly
         * inserting a second, duplicate key.
         */
        private val FIXED_ASPECT_RATIO_PATTERN = Regex("\"fixedAspectRatio\"\\s*:\\s*(true|false)")
    }

    /**
     * Pure, no-I/O core: given the original config's own text and a
     * profile, decides which known mitigations apply, transforms the
     * text accordingly, and returns the result. Safe to call from a
     * plain JVM unit test — no Android dependency of any kind.
     *
     * [target] (Sprint 53.1) decides only which [OverlayStatus] a
     * successful generation reports — [OverlayGenerationTarget.TEST]
     * (the default, preserving every existing caller and test)  reports
     * [OverlayStatus.GENERATED_TEST_ONLY]; [OverlayGenerationTarget.PRODUCTION]
     * reports [OverlayStatus.GENERATED_PRODUCTION]. No transformation
     * logic differs between the two — this is a reporting distinction
     * only, so [RuntimeLaunchPreparer] (the only production caller) can
     * tell a real launch-path generation apart from a test-only one
     * without a second code path.
     */
    fun generateOverlay(
        originalConfigText: String,
        profile: RuntimeConfigProfile,
        target: OverlayGenerationTarget = OverlayGenerationTarget.TEST
    ): OverlayGenerationResult {
        val requestedMitigations = (profile.enabledMitigations - profile.disabledMitigations.toSet())
            .distinct()

        if (requestedMitigations.isEmpty()) {
            return OverlayGenerationResult(
                overlayConfigText = null,
                generatedAuxiliaryFiles = emptyMap(),
                audit = MitigationAudit(
                    originalConfigHash = sha256Hex(originalConfigText),
                    overlayConfigHash = null,
                    lastAppliedMitigations = emptyList(),
                    lastReason = "No enabled mitigations requested for this profile.",
                    lastGeneratedAt = null
                ),
                overlayStatus = OverlayStatus.NOT_GENERATED
            )
        }

        return try {
            applyMitigations(originalConfigText, requestedMitigations, target)
        } catch (t: Throwable) {
            // Sprint 26 hardening: any unexpected failure during
            // transformation (e.g. a config text so malformed that even
            // the targeted regexes/brace-scan above can't make sense of
            // it) must never propagate as a crash, and must never leave
            // the caller thinking an overlay was generated. The original
            // text itself was never touched by construction — generateOverlay
            // only ever reads its own originalConfigText parameter, it
            // has no way to write back to wherever that text came from.
            OverlayGenerationResult(
                overlayConfigText = null,
                generatedAuxiliaryFiles = emptyMap(),
                audit = MitigationAudit(
                    originalConfigHash = runCatching { sha256Hex(originalConfigText) }.getOrNull(),
                    overlayConfigHash = null,
                    lastAppliedMitigations = emptyList(),
                    lastReason = "Overlay generation failed: ${t::class.simpleName}: ${t.message}",
                    lastGeneratedAt = null
                ),
                overlayStatus = OverlayStatus.ERROR,
                errorMessage = "Overlay generation failed: ${t::class.simpleName}: ${t.message}"
            )
        }
    }

    /**
     * The actual mitigation-application logic, separated from
     * [generateOverlay] purely so that method's own try/catch (Sprint 26
     * hardening) has a single call to wrap, rather than needing to catch
     * around a long, multi-mitigation body inline.
     */
    private fun applyMitigations(
        originalConfigText: String,
        requestedMitigations: List<String>,
        target: OverlayGenerationTarget
    ): OverlayGenerationResult {
        val appliedMitigations = mutableListOf<String>()
        val skippedMitigations = mutableListOf<String>()
        val reasons = mutableListOf<String>()
        val auxiliaryFiles = mutableMapOf<String, String>()

        var workingText = originalConfigText

        if (KnownMitigationIds.ZLIB_PRELOAD in requestedMitigations) {
            val (afterZlib, outcome, reason) = applyZlibPreloadMitigation(workingText)
            workingText = afterZlib
            when (outcome) {
                ZlibPreloadOutcome.INSERTED, ZlibPreloadOutcome.APPENDED -> {
                    auxiliaryFiles[ZLIB_PRELOAD_SCRIPT_FILE_NAME] = ZLIB_PRELOAD_SCRIPT_CONTENT
                    appliedMitigations += KnownMitigationIds.ZLIB_PRELOAD
                }
                ZlibPreloadOutcome.ALREADY_PRESENT -> {
                    skippedMitigations += KnownMitigationIds.ZLIB_PRELOAD
                }
            }
            reasons += reason
        }

        var originalWindowTitleForAudit: String? = null
        if (KnownMitigationIds.ASCII_SAFE_WINDOW_TITLE in requestedMitigations) {
            val match = WINDOW_TITLE_PATTERN.find(workingText)
            if (match == null) {
                skippedMitigations += KnownMitigationIds.ASCII_SAFE_WINDOW_TITLE
                reasons += "${KnownMitigationIds.ASCII_SAFE_WINDOW_TITLE}: skipped — no \"windowTitle\" key found in the original config to replace."
            } else {
                val originalTitle = unescapeJsonString(match.groupValues[1])
                originalWindowTitleForAudit = originalTitle
                val asciiSafeTitle = toAsciiSafe(originalTitle)
                if (asciiSafeTitle == originalTitle) {
                    skippedMitigations += KnownMitigationIds.ASCII_SAFE_WINDOW_TITLE
                    reasons += "${KnownMitigationIds.ASCII_SAFE_WINDOW_TITLE}: skipped — original windowTitle (\"$originalTitle\") is already plain ASCII; no change needed."
                } else {
                    workingText = workingText.replaceRange(
                        match.groups[1]!!.range,
                        escapeJsonString(asciiSafeTitle)
                    )
                    appliedMitigations += KnownMitigationIds.ASCII_SAFE_WINDOW_TITLE
                    reasons += "${KnownMitigationIds.ASCII_SAFE_WINDOW_TITLE}: applied — replaced non-ASCII windowTitle (\"$originalTitle\") with ASCII-safe (\"$asciiSafeTitle\")."
                }
            }
        }

        if (KnownMitigationIds.ASPECT_FIT_RENDER in requestedMitigations) {
            val (afterAspectFit, applied, reason) = applyAspectFitRenderMitigation(workingText)
            workingText = afterAspectFit
            if (applied) {
                appliedMitigations += KnownMitigationIds.ASPECT_FIT_RENDER
            } else {
                skippedMitigations += KnownMitigationIds.ASPECT_FIT_RENDER
            }
            reasons += reason
        }

        if (KnownMitigationIds.SPRINT41_INPUT_DIAGNOSTIC in requestedMitigations) {
            val (afterDiagnostic, applied, reason) = applySprint41InputDiagnosticMitigation(workingText)
            workingText = afterDiagnostic
            if (applied) {
                appliedMitigations += KnownMitigationIds.SPRINT41_INPUT_DIAGNOSTIC
                auxiliaryFiles[SPRINT41_DIAGNOSTIC_SCRIPT_FILE_NAME] = SPRINT41_DIAGNOSTIC_SCRIPT_CONTENT
            } else {
                skippedMitigations += KnownMitigationIds.SPRINT41_INPUT_DIAGNOSTIC
            }
            reasons += reason
        }

        if (KnownMitigationIds.SPRINT42_MOVEMENT_PATH_DIAGNOSTIC in requestedMitigations) {
            val (afterDiagnostic, applied, reason) = applySprint42MovementPathDiagnosticMitigation(workingText)
            workingText = afterDiagnostic
            if (applied) {
                appliedMitigations += KnownMitigationIds.SPRINT42_MOVEMENT_PATH_DIAGNOSTIC
                auxiliaryFiles[SPRINT42_DIAGNOSTIC_SCRIPT_FILE_NAME] = SPRINT42_DIAGNOSTIC_SCRIPT_CONTENT
            } else {
                skippedMitigations += KnownMitigationIds.SPRINT42_MOVEMENT_PATH_DIAGNOSTIC
            }
            reasons += reason
        }

        if (KnownMitigationIds.SPRINT43_COMMAND_PIPELINE_DIAGNOSTIC in requestedMitigations) {
            val (afterDiagnostic, applied, reason) = applySprint43CommandPipelineDiagnosticMitigation(workingText)
            workingText = afterDiagnostic
            if (applied) {
                appliedMitigations += KnownMitigationIds.SPRINT43_COMMAND_PIPELINE_DIAGNOSTIC
                auxiliaryFiles[SPRINT43_DIAGNOSTIC_SCRIPT_FILE_NAME] = SPRINT43_DIAGNOSTIC_SCRIPT_CONTENT
            } else {
                skippedMitigations += KnownMitigationIds.SPRINT43_COMMAND_PIPELINE_DIAGNOSTIC
            }
            reasons += reason
        }

        if (KnownMitigationIds.SPRINT44_TIMEBASE_DIAGNOSTIC in requestedMitigations) {
            val (afterDiagnostic, applied, reason) = applySprint44TimebaseDiagnosticMitigation(workingText)
            workingText = afterDiagnostic
            if (applied) {
                appliedMitigations += KnownMitigationIds.SPRINT44_TIMEBASE_DIAGNOSTIC
                auxiliaryFiles[SPRINT44_DIAGNOSTIC_SCRIPT_FILE_NAME] = SPRINT44_DIAGNOSTIC_SCRIPT_CONTENT
            } else {
                skippedMitigations += KnownMitigationIds.SPRINT44_TIMEBASE_DIAGNOSTIC
            }
            reasons += reason
        }

        if (KnownMitigationIds.SPRINT45_FPS60_CAP_DIAGNOSTIC in requestedMitigations) {
            val (afterFps60Cap, applied, reason) = applySprint45Fps60CapDiagnosticMitigation(workingText)
            workingText = afterFps60Cap
            if (applied) {
                appliedMitigations += KnownMitigationIds.SPRINT45_FPS60_CAP_DIAGNOSTIC
            } else {
                skippedMitigations += KnownMitigationIds.SPRINT45_FPS60_CAP_DIAGNOSTIC
            }
            reasons += reason
        }

        if (KnownMitigationIds.SPRINT48_SYSTEM_UPTIME_SECONDS_SHIM in requestedMitigations) {
            val (afterShim, applied, reason) = applySprint48SystemUptimeSecondsShimMitigation(workingText)
            workingText = afterShim
            if (applied) {
                appliedMitigations += KnownMitigationIds.SPRINT48_SYSTEM_UPTIME_SECONDS_SHIM
                auxiliaryFiles[SPRINT48_SHIM_SCRIPT_FILE_NAME] = SPRINT48_SHIM_SCRIPT_CONTENT
            } else {
                skippedMitigations += KnownMitigationIds.SPRINT48_SYSTEM_UPTIME_SECONDS_SHIM
            }
            reasons += reason
        }

        if (KnownMitigationIds.SPRINT50_SPEED_CONTROL in requestedMitigations) {
            val (afterSpeedShim, applied, reason) = applySprint50SpeedControlMitigation(workingText)
            workingText = afterSpeedShim
            if (applied) {
                appliedMitigations += KnownMitigationIds.SPRINT50_SPEED_CONTROL
                auxiliaryFiles[SPRINT50_SHIM_SCRIPT_FILE_NAME] = SPRINT50_SHIM_SCRIPT_CONTENT
            } else {
                skippedMitigations += KnownMitigationIds.SPRINT50_SPEED_CONTROL
            }
            reasons += reason
        }


        val overlayStatus = when {
            appliedMitigations.isEmpty() -> OverlayStatus.NOT_GENERATED
            target == OverlayGenerationTarget.PRODUCTION -> OverlayStatus.GENERATED_PRODUCTION
            else -> OverlayStatus.GENERATED_TEST_ONLY
        }
        val overlayText = if (appliedMitigations.isEmpty()) null else workingText

        return OverlayGenerationResult(
            overlayConfigText = overlayText,
            generatedAuxiliaryFiles = auxiliaryFiles,
            audit = MitigationAudit(
                originalConfigHash = sha256Hex(originalConfigText),
                overlayConfigHash = overlayText?.let { sha256Hex(it) },
                lastAppliedMitigations = appliedMitigations,
                lastReason = (reasons + listOfNotNull(
                    originalWindowTitleForAudit?.let { "original windowTitle preserved in audit: \"$it\"" }
                )).joinToString(" | "),
                lastGeneratedAt = null // Stage 2 does not stamp a wall-clock time — left to the Stage 3 caller, which has a real launch context to timestamp against.
            ),
            overlayStatus = overlayStatus,
            skippedMitigations = skippedMitigations
        )
    }

    /**
     * Sprint 26 hardening. Unlike Sprint 25's own original behavior
     * (skip entirely if any `preloadScript` key already exists), this
     * now handles three real shapes: absent (insert fresh, same as
     * before), an existing JSON array (append our own script into it,
     * preserving whatever was already there), and an existing bare
     * string (convert to a two-element array containing both). In every
     * case, if our own script name is already present in whatever form
     * exists, this is a no-op (idempotent) rather than a duplicate.
     */
    private fun applyZlibPreloadMitigation(text: String): Triple<String, ZlibPreloadOutcome, String> {
        val match = PRELOAD_SCRIPT_VALUE_PATTERN.find(text)

        if (match == null) {
            val inserted = insertJsonProperty(text, "\"preloadScript\": [\"$ZLIB_PRELOAD_SCRIPT_FILE_NAME\"],")
            return Triple(
                inserted,
                ZlibPreloadOutcome.INSERTED,
                "${KnownMitigationIds.ZLIB_PRELOAD}: applied — inserted preloadScript pointing at a generated $ZLIB_PRELOAD_SCRIPT_FILE_NAME (require 'zlib', no raise)."
            )
        }

        val existingValueRaw = match.groupValues[1]
        if (existingValueRaw.contains("\"$ZLIB_PRELOAD_SCRIPT_FILE_NAME\"")) {
            return Triple(
                text,
                ZlibPreloadOutcome.ALREADY_PRESENT,
                "${KnownMitigationIds.ZLIB_PRELOAD}: skipped — $ZLIB_PRELOAD_SCRIPT_FILE_NAME is already referenced in the existing preloadScript value; not duplicated."
            )
        }

        val newValue = if (existingValueRaw.startsWith("[")) {
            val inner = existingValueRaw.substring(1, existingValueRaw.length - 1).trimEnd()
            val newInner = when {
                inner.isEmpty() -> "\"$ZLIB_PRELOAD_SCRIPT_FILE_NAME\""
                inner.endsWith(",") -> "$inner \"$ZLIB_PRELOAD_SCRIPT_FILE_NAME\""
                else -> "$inner, \"$ZLIB_PRELOAD_SCRIPT_FILE_NAME\""
            }
            "[$newInner]"
        } else {
            // Bare string form — convert to a two-element array preserving the existing entry.
            "[$existingValueRaw, \"$ZLIB_PRELOAD_SCRIPT_FILE_NAME\"]"
        }

        val appended = text.replaceRange(match.groups[1]!!.range, newValue)
        return Triple(
            appended,
            ZlibPreloadOutcome.APPENDED,
            "${KnownMitigationIds.ZLIB_PRELOAD}: applied — appended $ZLIB_PRELOAD_SCRIPT_FILE_NAME to the existing preloadScript value ($existingValueRaw), preserving what was already there."
        )
    }

    private enum class ZlibPreloadOutcome { INSERTED, APPENDED, ALREADY_PRESENT }

    /**
     * Sprint 50 — inserts SPRINT50_SHIM_SCRIPT_FILE_NAME immediately after
     * SPRINT48_SHIM_SCRIPT_FILE_NAME in preloadScript so speed control composes
     * on top of Sprint 48's seconds shim. If Sprint 48 is absent, it defensively
     * prepends Sprint50; the Ruby shim itself can fall back to seconds conversion.
     */
    private fun applySprint50SpeedControlMitigation(text: String): Triple<String, Boolean, String> {
        val match = PRELOAD_SCRIPT_VALUE_PATTERN.find(text)

        if (match == null) {
            val inserted = insertJsonProperty(text, "\"preloadScript\": [\"$SPRINT50_SHIM_SCRIPT_FILE_NAME\"],")
            return Triple(
                inserted,
                true,
                "${KnownMitigationIds.SPRINT50_SPEED_CONTROL}: applied — inserted a new preloadScript array containing $SPRINT50_SHIM_SCRIPT_FILE_NAME."
            )
        }

        val existingValue = match.groupValues[1]
        if (existingValue.contains("\"$SPRINT50_SHIM_SCRIPT_FILE_NAME\"")) {
            return Triple(
                text,
                true,
                "${KnownMitigationIds.SPRINT50_SPEED_CONTROL}: applied — $SPRINT50_SHIM_SCRIPT_FILE_NAME already present."
            )
        }

        val newValue = if (existingValue.startsWith("[")) {
            val inner = existingValue.removeSurrounding("[", "]").trim()
            when {
                inner.isEmpty() -> "[\"$SPRINT50_SHIM_SCRIPT_FILE_NAME\"]"
                inner.contains("\"$SPRINT48_SHIM_SCRIPT_FILE_NAME\"") -> {
                    val parts = inner.split(",").map { it.trim() }.toMutableList()
                    val sprint48Index = parts.indexOfFirst { it == "\"$SPRINT48_SHIM_SCRIPT_FILE_NAME\"" }
                    parts.add(sprint48Index + 1, "\"$SPRINT50_SHIM_SCRIPT_FILE_NAME\"")
                    "[${parts.joinToString(", ")}]"
                }
                else -> "[\"$SPRINT50_SHIM_SCRIPT_FILE_NAME\", $inner]"
            }
        } else {
            "[\"$SPRINT50_SHIM_SCRIPT_FILE_NAME\", $existingValue]"
        }

        val updated = text.replaceRange(match.groups[1]!!.range, newValue)
        return Triple(
            updated,
            true,
            "${KnownMitigationIds.SPRINT50_SPEED_CONTROL}: applied — inserted $SPRINT50_SHIM_SCRIPT_FILE_NAME after Sprint 48's own entry when present, otherwise prepended."
        )
    }

    /**
     * Sprint 48 — prepends System.uptime seconds shim to preloadScript.
     * Ordering matters: this must run before any diagnostic script that reads
     * System.uptime/System.delta.
     */
    private fun applySprint48SystemUptimeSecondsShimMitigation(text: String): Triple<String, Boolean, String> {
        val match = PRELOAD_SCRIPT_VALUE_PATTERN.find(text)

        if (match == null) {
            val inserted = insertJsonProperty(text, "\"preloadScript\": [\"$SPRINT48_SHIM_SCRIPT_FILE_NAME\"],")
            return Triple(
                inserted,
                true,
                "${KnownMitigationIds.SPRINT48_SYSTEM_UPTIME_SECONDS_SHIM}: applied — inserted a new preloadScript array containing $SPRINT48_SHIM_SCRIPT_FILE_NAME."
            )
        }

        val existingValue = match.groupValues[1]

        if (existingValue.contains("\"$SPRINT48_SHIM_SCRIPT_FILE_NAME\"")) {
            return Triple(
                text,
                true,
                "${KnownMitigationIds.SPRINT48_SYSTEM_UPTIME_SECONDS_SHIM}: applied — $SPRINT48_SHIM_SCRIPT_FILE_NAME already present in preloadScript."
            )
        }

        val newValue = if (existingValue.startsWith("[")) {
            val inner = existingValue.removeSurrounding("[", "]").trim()
            if (inner.isEmpty()) {
                "[\"$SPRINT48_SHIM_SCRIPT_FILE_NAME\"]"
            } else {
                "[\"$SPRINT48_SHIM_SCRIPT_FILE_NAME\", $inner]"
            }
        } else {
            "[\"$SPRINT48_SHIM_SCRIPT_FILE_NAME\", $existingValue]"
        }

        val updated = text.replaceRange(match.groups[1]!!.range, newValue)
        return Triple(
            updated,
            true,
            "${KnownMitigationIds.SPRINT48_SYSTEM_UPTIME_SECONDS_SHIM}: applied — prepended $SPRINT48_SHIM_SCRIPT_FILE_NAME to the existing preloadScript value, ensuring it runs first."
        )
    }

    /**
     * Sprint 45 — forces syncToRefreshrate:false and fixedFramerate:60
     * directly in the overlay config, so the value is applied by overlay
     * regeneration rather than reverted by it. Never touches "vsync".
     */
    /**
     * Sprint 45.1 fix: correctly distinguishes a commented-out
     * "fixedFramerate" line from a genuinely active one. Sprint 45's
     * original regex matched both active and commented forms, leaving
     * // "fixedFramerate": 60, inert while reporting the mitigation as applied.
     */
    private fun applySprint45Fps60CapDiagnosticMitigation(text: String): Triple<String, Boolean, String> {
        var workingText = text

        val syncMatch = SYNC_TO_REFRESHRATE_PATTERN.find(workingText)
        workingText = when {
            syncMatch == null -> insertJsonProperty(workingText, "\"syncToRefreshrate\": false,")
            syncMatch.groupValues[1] == "false" -> workingText
            else -> workingText.replaceRange(syncMatch.groups[1]!!.range, "false")
        }

        val allMatches = FIXED_FRAMERATE_ANY_OCCURRENCE_PATTERN.findAll(workingText).toList()
        var activeMatch: MatchResult? = null
        var commentedMatch: MatchResult? = null

        for (m in allMatches) {
            val lineStart = workingText.lastIndexOf('\n', (m.range.first - 1).coerceAtLeast(0)).let {
                if (it == -1) 0 else it + 1
            }
            val linePrefix = workingText.substring(lineStart, m.range.first)
            if (linePrefix.contains("//")) {
                commentedMatch = m
            } else {
                activeMatch = m
            }
        }

        workingText = when {
            activeMatch != null -> {
                if (activeMatch.groupValues[1] == "60") {
                    workingText
                } else {
                    workingText.replaceRange(activeMatch.groups[1]!!.range, "60")
                }
            }
            commentedMatch != null -> {
                val lineStart = workingText.lastIndexOf('\n', (commentedMatch.range.first - 1).coerceAtLeast(0)).let {
                    if (it == -1) 0 else it + 1
                }
                var lineEnd = workingText.indexOf('\n', commentedMatch.range.last)
                if (lineEnd == -1) lineEnd = workingText.length

                val oldLine = workingText.substring(lineStart, lineEnd)
                val indent = oldLine.takeWhile { it == ' ' || it == '\t' }
                val newLine = "$indent\"fixedFramerate\": 60,"

                workingText.substring(0, lineStart) + newLine + workingText.substring(lineEnd)
            }
            else -> insertJsonProperty(workingText, "\"fixedFramerate\": 60,")
        }

        return Triple(
            workingText,
            true,
            "SPRINT45_FPS60_CAP_DIAG applied — syncToRefreshrate=false, fixedFramerate=60."
        )
    }

    /**
     * Sprint 44 — appends the timebase diagnostic Ruby script to the
     * overlay's preloadScript value and emits the script as an auxiliary file
     * for the disposable workspace. It never mutates original game files.
     */
    private fun applySprint44TimebaseDiagnosticMitigation(text: String): Triple<String, Boolean, String> {
        val match = PRELOAD_SCRIPT_VALUE_PATTERN.find(text)

        if (match == null) {
            val inserted = insertJsonProperty(text, "\"preloadScript\": [\"$SPRINT44_DIAGNOSTIC_SCRIPT_FILE_NAME\"],")
            return Triple(
                inserted,
                true,
                "${KnownMitigationIds.SPRINT44_TIMEBASE_DIAGNOSTIC}: applied — inserted preloadScript containing $SPRINT44_DIAGNOSTIC_SCRIPT_FILE_NAME."
            )
        }

        val existingValue = match.groupValues[1]
        if (existingValue.contains("\"$SPRINT44_DIAGNOSTIC_SCRIPT_FILE_NAME\"")) {
            return Triple(
                text,
                true,
                "${KnownMitigationIds.SPRINT44_TIMEBASE_DIAGNOSTIC}: applied — $SPRINT44_DIAGNOSTIC_SCRIPT_FILE_NAME already present in preloadScript."
            )
        }

        val newValue = if (existingValue.startsWith("[")) {
            val inner = existingValue.substring(1, existingValue.length - 1).trimEnd()
            val newInner = when {
                inner.isEmpty() -> "\"$SPRINT44_DIAGNOSTIC_SCRIPT_FILE_NAME\""
                inner.endsWith(",") -> "$inner \"$SPRINT44_DIAGNOSTIC_SCRIPT_FILE_NAME\""
                else -> "$inner, \"$SPRINT44_DIAGNOSTIC_SCRIPT_FILE_NAME\""
            }
            "[$newInner]"
        } else {
            "[$existingValue, \"$SPRINT44_DIAGNOSTIC_SCRIPT_FILE_NAME\"]"
        }

        val updated = text.replaceRange(match.groups[1]!!.range, newValue)
        return Triple(
            updated,
            true,
            "${KnownMitigationIds.SPRINT44_TIMEBASE_DIAGNOSTIC}: applied — appended $SPRINT44_DIAGNOSTIC_SCRIPT_FILE_NAME to existing preloadScript."
        )
    }

    /**
     * Sprint 43 — appends the command pipeline diagnostic Ruby script to the
     * overlay's preloadScript value and emits the script as an auxiliary file
     * for the disposable workspace. It never mutates original game files.
     */
    private fun applySprint43CommandPipelineDiagnosticMitigation(text: String): Triple<String, Boolean, String> {
        val match = PRELOAD_SCRIPT_VALUE_PATTERN.find(text)

        if (match == null) {
            val inserted = insertJsonProperty(text, "\"preloadScript\": [\"$SPRINT43_DIAGNOSTIC_SCRIPT_FILE_NAME\"],")
            return Triple(
                inserted,
                true,
                "${KnownMitigationIds.SPRINT43_COMMAND_PIPELINE_DIAGNOSTIC}: applied — inserted preloadScript containing $SPRINT43_DIAGNOSTIC_SCRIPT_FILE_NAME."
            )
        }

        val existingValue = match.groupValues[1]
        if (existingValue.contains("\"$SPRINT43_DIAGNOSTIC_SCRIPT_FILE_NAME\"")) {
            return Triple(
                text,
                true,
                "${KnownMitigationIds.SPRINT43_COMMAND_PIPELINE_DIAGNOSTIC}: applied — $SPRINT43_DIAGNOSTIC_SCRIPT_FILE_NAME already present in preloadScript."
            )
        }

        val newValue = if (existingValue.startsWith("[")) {
            val inner = existingValue.substring(1, existingValue.length - 1).trimEnd()
            val newInner = when {
                inner.isEmpty() -> "\"$SPRINT43_DIAGNOSTIC_SCRIPT_FILE_NAME\""
                inner.endsWith(",") -> "$inner \"$SPRINT43_DIAGNOSTIC_SCRIPT_FILE_NAME\""
                else -> "$inner, \"$SPRINT43_DIAGNOSTIC_SCRIPT_FILE_NAME\""
            }
            "[$newInner]"
        } else {
            "[$existingValue, \"$SPRINT43_DIAGNOSTIC_SCRIPT_FILE_NAME\"]"
        }

        val updated = text.replaceRange(match.groups[1]!!.range, newValue)
        return Triple(
            updated,
            true,
            "${KnownMitigationIds.SPRINT43_COMMAND_PIPELINE_DIAGNOSTIC}: applied — appended $SPRINT43_DIAGNOSTIC_SCRIPT_FILE_NAME to existing preloadScript."
        )
    }

    /**
     * Sprint 42 — appends the movement path diagnostic Ruby script to the
     * overlay's preloadScript value and emits the script as an auxiliary file
     * for the disposable workspace. It never mutates original game files.
     */
    private fun applySprint42MovementPathDiagnosticMitigation(text: String): Triple<String, Boolean, String> {
        val match = PRELOAD_SCRIPT_VALUE_PATTERN.find(text)

        if (match == null) {
            val inserted = insertJsonProperty(text, "\"preloadScript\": [\"$SPRINT42_DIAGNOSTIC_SCRIPT_FILE_NAME\"],")
            return Triple(
                inserted,
                true,
                "${KnownMitigationIds.SPRINT42_MOVEMENT_PATH_DIAGNOSTIC}: applied — inserted preloadScript containing $SPRINT42_DIAGNOSTIC_SCRIPT_FILE_NAME."
            )
        }

        val existingValue = match.groupValues[1]
        if (existingValue.contains("\"$SPRINT42_DIAGNOSTIC_SCRIPT_FILE_NAME\"")) {
            return Triple(
                text,
                true,
                "${KnownMitigationIds.SPRINT42_MOVEMENT_PATH_DIAGNOSTIC}: applied — $SPRINT42_DIAGNOSTIC_SCRIPT_FILE_NAME already present in preloadScript."
            )
        }

        val newValue = if (existingValue.startsWith("[")) {
            val inner = existingValue.substring(1, existingValue.length - 1).trimEnd()
            val newInner = when {
                inner.isEmpty() -> "\"$SPRINT42_DIAGNOSTIC_SCRIPT_FILE_NAME\""
                inner.endsWith(",") -> "$inner \"$SPRINT42_DIAGNOSTIC_SCRIPT_FILE_NAME\""
                else -> "$inner, \"$SPRINT42_DIAGNOSTIC_SCRIPT_FILE_NAME\""
            }
            "[$newInner]"
        } else {
            "[$existingValue, \"$SPRINT42_DIAGNOSTIC_SCRIPT_FILE_NAME\"]"
        }

        val updated = text.replaceRange(match.groups[1]!!.range, newValue)
        return Triple(
            updated,
            true,
            "${KnownMitigationIds.SPRINT42_MOVEMENT_PATH_DIAGNOSTIC}: applied — appended $SPRINT42_DIAGNOSTIC_SCRIPT_FILE_NAME to existing preloadScript."
        )
    }

    /**
     * Sprint 41 — appends the movement diagnostic Ruby script to the overlay's
     * preloadScript value and emits the script as an auxiliary file for the
     * disposable workspace. It never mutates original game files.
     */
    private fun applySprint41InputDiagnosticMitigation(text: String): Triple<String, Boolean, String> {
        val match = PRELOAD_SCRIPT_VALUE_PATTERN.find(text)

        if (match == null) {
            val inserted = insertJsonProperty(text, "\"preloadScript\": [\"$SPRINT41_DIAGNOSTIC_SCRIPT_FILE_NAME\"],")
            return Triple(
                inserted,
                true,
                "${KnownMitigationIds.SPRINT41_INPUT_DIAGNOSTIC}: applied — inserted preloadScript containing $SPRINT41_DIAGNOSTIC_SCRIPT_FILE_NAME."
            )
        }

        val existingValue = match.groupValues[1]
        if (existingValue.contains("\"$SPRINT41_DIAGNOSTIC_SCRIPT_FILE_NAME\"")) {
            return Triple(
                text,
                true,
                "${KnownMitigationIds.SPRINT41_INPUT_DIAGNOSTIC}: applied — $SPRINT41_DIAGNOSTIC_SCRIPT_FILE_NAME already present in preloadScript."
            )
        }

        val newValue = if (existingValue.startsWith("[")) {
            val inner = existingValue.substring(1, existingValue.length - 1).trimEnd()
            val newInner = when {
                inner.isEmpty() -> "\"$SPRINT41_DIAGNOSTIC_SCRIPT_FILE_NAME\""
                inner.endsWith(",") -> "$inner \"$SPRINT41_DIAGNOSTIC_SCRIPT_FILE_NAME\""
                else -> "$inner, \"$SPRINT41_DIAGNOSTIC_SCRIPT_FILE_NAME\""
            }
            "[$newInner]"
        } else {
            "[$existingValue, \"$SPRINT41_DIAGNOSTIC_SCRIPT_FILE_NAME\"]"
        }

        val updated = text.replaceRange(match.groups[1]!!.range, newValue)
        return Triple(
            updated,
            true,
            "${KnownMitigationIds.SPRINT41_INPUT_DIAGNOSTIC}: applied — appended $SPRINT41_DIAGNOSTIC_SCRIPT_FILE_NAME to existing preloadScript."
        )
    }

    /**
     * Sprint 30 — sets `"fixedAspectRatio": true` in the overlay config.
     * Handles three shapes: absent (insert fresh), present as `false`
     * (replace with `true`), present as `true` already (no-op, skip
     * cleanly rather than inserting a duplicate key). Never touches any
     * other config value — in particular, this does not set
     * `"fullscreen"` or any window-flag-related key, which remains a
     * separate, not-yet-confirmed concern per the Sprint 30 planning
     * document's own risk notes.
     */
    private fun applyAspectFitRenderMitigation(text: String): Triple<String, Boolean, String> {
        val match = FIXED_ASPECT_RATIO_PATTERN.find(text)

        if (match == null) {
            val inserted = insertJsonProperty(text, "\"fixedAspectRatio\": true,")
            return Triple(
                inserted,
                true,
                "${KnownMitigationIds.ASPECT_FIT_RENDER}: applied — inserted \"fixedAspectRatio\": true (was absent from the original config)."
            )
        }

        val existingValue = match.groupValues[1]
        if (existingValue == "true") {
            // Sprint 30.2 fix: this branch was previously returning
            // `applied=false` ("skipped, no change needed"), which was
            // an audit/contract bug — this mitigation's own goal is a
            // *state* ("fixedAspectRatio is true"), not an *action*
            // ("insert or replace a value"). If that state already
            // holds, the mitigation's own goal is genuinely satisfied
            // and must be recorded as applied, exactly the same as if
            // this method had inserted or replaced the value itself —
            // otherwise a profile requesting only aspect-fit-render
            // against a config that already has fixedAspectRatio:true
            // would incorrectly produce an empty appliedMitigations
            // list and OverlayStatus.NOT_GENERATED, even though the
            // real-world outcome the caller asked for is already true.
            return Triple(
                text,
                true,
                "${KnownMitigationIds.ASPECT_FIT_RENDER}: applied — fixedAspectRatio was already true in the original config; confirmed, no text change needed."
            )
        }

        val replaced = text.replaceRange(match.groups[1]!!.range, "true")
        return Triple(
            replaced,
            true,
            "${KnownMitigationIds.ASPECT_FIT_RENDER}: applied — replaced fixedAspectRatio: false with true."
        )
    }

    /**
     * Thin [File]-based convenience wrapper around [generateOverlay].
     * Reads [originalConfigFile]'s own text (never writes back to it),
     * and — only if at least one mitigation was actually applied —
     * writes the overlay config and any generated auxiliary files (e.g.
     * the Zlib preload script) into [outputDirectory]. Still plain
     * `java.io.File` I/O, no Android-specific API — safe to call from a
     * plain JVM unit test using a temp directory, not just from a real
     * app-private location.
     */
    fun generateOverlayToDirectory(
        originalConfigFile: File,
        profile: RuntimeConfigProfile,
        outputDirectory: File,
        target: OverlayGenerationTarget = OverlayGenerationTarget.TEST
    ): OverlayGenerationResult {
        val originalText = try {
            originalConfigFile.readText(Charsets.UTF_8)
        } catch (t: Throwable) {
            return OverlayGenerationResult(
                overlayConfigText = null,
                generatedAuxiliaryFiles = emptyMap(),
                audit = MitigationAudit(),
                overlayStatus = OverlayStatus.ERROR,
                errorMessage = "Failed to read original config at ${originalConfigFile.absolutePath}: ${t.message}"
            )
        }

        val result = generateOverlay(originalText, profile, target)

        val isGenerated = result.overlayStatus == OverlayStatus.GENERATED_TEST_ONLY ||
            result.overlayStatus == OverlayStatus.GENERATED_PRODUCTION
        if (isGenerated && result.overlayConfigText != null) {
            try {
                outputDirectory.mkdirs()
                File(outputDirectory, OVERLAY_CONFIG_FILE_NAME).writeText(result.overlayConfigText, Charsets.UTF_8)
                result.generatedAuxiliaryFiles.forEach { (fileName, content) ->
                    File(outputDirectory, fileName).writeText(content, Charsets.UTF_8)
                }
            } catch (t: Throwable) {
                return result.copy(
                    overlayStatus = OverlayStatus.ERROR,
                    errorMessage = "Overlay content generated successfully but failed to write to ${outputDirectory.absolutePath}: ${t.message}"
                )
            }
        }

        return result
    }

    // --- Private helpers ---

    /**
     * Same safe, text-based insertion technique already proven across
     * Sprint 21–23's own diagnostic tests (`RuntimeActivityPreloadZlibProbeTest`
     * and its successors) — finds the final top-level closing brace, adds
     * a trailing comma to the preceding content only if one isn't
     * already present, and inserts the new property directly before that
     * brace. Every other character, comment, and formatting choice in
     * the rest of the file is left untouched.
     */
    private fun insertJsonProperty(originalText: String, propertyLine: String): String {
        val lastBraceIndex = originalText.lastIndexOf('}')
        require(lastBraceIndex >= 0) { "No closing brace found in config text — cannot insert property." }

        val before = originalText.substring(0, lastBraceIndex)
        val after = originalText.substring(lastBraceIndex)

        val trimmedBefore = before.trimEnd()
        val lastChar = trimmedBefore.lastOrNull()
        val needsComma = lastChar != null && lastChar != ',' && lastChar != '{'

        val indentedProperty = "    $propertyLine"
        return if (needsComma) {
            "$trimmedBefore,\n$indentedProperty\n$after"
        } else {
            "$trimmedBefore\n$indentedProperty\n$after"
        }
    }

    /** Deterministic ASCII transliteration: decomposes accented characters (e.g. "é" -> "e") via Unicode normalization, then strips anything still non-ASCII. */
    private fun toAsciiSafe(value: String): String {
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
        return decomposed.filter { it.code in 0..127 }
    }

    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Minimal, targeted unescaping for the specific JSON string-escape sequences that could realistically appear in a windowTitle value. */
    private fun unescapeJsonString(raw: String): String =
        raw.replace("\\\"", "\"").replace("\\\\", "\\")

    private fun escapeJsonString(raw: String): String =
        raw.replace("\\", "\\\\").replace("\"", "\\\"")
}

/**
 * The result of [RuntimeConfigOverlayService.generateOverlay] — the
 * "audit result" described in the Sprint 25 approved scope. [overlayConfigText]
 * is `null` whenever [overlayStatus] is [OverlayStatus.NOT_GENERATED] or
 * [OverlayStatus.ERROR] — matching the requirement that a no-mitigation
 * profile produces no overlay at all, not an empty or unchanged one.
 */
data class OverlayGenerationResult(
    val overlayConfigText: String?,
    val generatedAuxiliaryFiles: Map<String, String>,
    val audit: MitigationAudit,
    val overlayStatus: OverlayStatus,
    val skippedMitigations: List<String> = emptyList(),
    val errorMessage: String? = null
)
