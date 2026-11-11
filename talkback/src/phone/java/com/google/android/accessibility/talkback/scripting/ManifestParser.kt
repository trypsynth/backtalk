/*
 * Copyright 2026 Backtalk contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback.scripting

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class ManifestParser(private val strict: Boolean) {
  /**
   * Fields this Backtalk doesn't know, from a script written for a newer one. What holds them is
   * left out: an unknown field of a rule or navigation item is ignored, and a match with one drops
   * its rule, action or navigation item, since ignoring the field would match more items.
   */
  val unsupported = LinkedHashSet<String>()

  // Counts every field left out, so that a rule can tell whether it lost any.
  private var leftOut = 0

  private class UnsupportedField(val field: String) : Exception()

  fun parse(json: String): ScriptManifest {
    val obj =
      try {
        JSONObject(json)
      } catch (e: JSONException) {
        manifestError("The manifest is not an object")
      }
    val id = obj.requiredString("id")
    manifestCheck(ID_PATTERN.matches(id)) {
      "The id must be lowercase letters, digits, dots, dashes or underscores: $id"
    }
    val permissions =
      obj.stringList("permissions")
        .map { ScriptPermission.fromKey(it) ?: manifestError("Unknown permission: $it") }
        .toSet()
    // minApiVersion and testedApiVersion, from before minBuild, are ignored.
    val minBuild = minBuild(obj)
    val homepage = obj.optionalString("homepage")
    manifestCheck(homepage == null || HOMEPAGE_PATTERN.matches(homepage)) {
      "The homepage must be an http or https address: $homepage"
    }
    return ScriptManifest(
      id = id,
      name = obj.requiredString("name"),
      version = obj.optString("version", "1"),
      description = obj.optionalString("description"),
      author = if (strict) obj.requiredString("author") else obj.optionalString("author") ?: "?",
      homepage = homepage,
      minBuild = minBuild,
      apps = apps(obj.opt("apps")),
      permissions = permissions,
      settings = settings(obj.optJSONArray("settings")),
      rules = rules(obj.optJSONArray("rules")),
      commands = commands(obj.optJSONArray("commands"), permissions),
      navigation = navigation(obj.optJSONArray("navigation"), permissions),
      unsupported = unsupported.toList(),
      json = obj.toString(),
    )
  }

  /** Returns the rule, or null if it has nothing left once [unsupported] fields are left out. */
  fun rule(json: JSONObject): ScriptRule? {
    val leftOutBefore = leftOut
    json.unknownKeys(RULE_KEYS).forEach(::leaveOut)
    val rule =
      unlessUnsupported {
        ScriptRule(
          id = json.optionalString("id"),
          match = json.optJSONObject("match")?.let { query(it) },
          window = json.optionalString("window"),
          activity = json.optionalString("activity"),
          label = json.optionalString("label"),
          speak = json.optionalString("speak"),
          hide = hideOf(json.opt("hide"), "hide"),
          silence = hideOf(json.opt("silence"), "silence"),
          role = json.optionalString("role"),
          state = json.optionalString("state"),
          hint = json.optionalString("hint"),
          heading = if (json.isNull("heading")) null else json.getBoolean("heading"),
          group = json.optBoolean("group", false),
          readBefore = targetOf(json.opt("readBefore")),
          readAfter = targetOf(json.opt("readAfter")),
          actions = itemActions(json.optJSONArray("actions")),
          activate = json.optJSONObject("activate")?.let { query(it) },
          liftToActivate = json.optBoolean("liftToActivate", false),
          longPressAfter = longPressAfterOf(json.opt("longPressAfter")),
        )
      } ?: return null
    with(rule) {
      manifestCheck(id != null || match != null) { "A rule needs id or match" }
      checkRole(role)
      manifestCheck(readBefore == null || readAfter == null) {
        "The rule for $target has both readBefore and readAfter"
      }
      if (!changesItem && actions.isEmpty() && leftOut > leftOutBefore) return null
      manifestCheck(changesItem || actions.isNotEmpty()) {
        "The rule for $target needs label, speak, hide, silence, role, state, hint, heading, " +
          "group, readBefore, readAfter, activate, liftToActivate, longPressAfter or actions"
      }
    }
    return rule
  }

  private fun minBuild(obj: JSONObject): Int? =
    if (obj.isNull("minBuild")) {
      null
    } else {
      (obj.opt("minBuild") as? Number)
        ?.takeIf { it.toDouble() == it.toInt().toDouble() && it.toInt() >= 1 }
        ?.toInt()
        ?: manifestError("minBuild is a Backtalk build number, such as ${ScriptManifest.BUILD}")
    }

  private fun apps(value: Any?): List<AppMatcher> =
    when (value) {
      null,
      JSONObject.NULL -> emptyList()
      is String -> listOf(value)
      is JSONArray -> value.items()
      else -> manifestError("apps must be a list")
    }.map { app ->
      when (app) {
        is String -> AppMatcher(packageName(app), null, null)
        is JSONObject ->
          AppMatcher(
            packageName(app.requiredString("package")),
            app.optionalString("activity"),
            app.optionalString("window"),
          )
        else -> manifestError("Each app must be a package name or an object")
      }
    }

  private fun settings(array: JSONArray?): List<ScriptSetting> {
    val keys = HashSet<String>()
    return array.objects("setting").map { setting ->
      val key = uniqueKey(setting.requiredString("key"), keys, "Setting keys")
      val typeKey = setting.optString("type", "switch")
      val type =
        ScriptSetting.Type.entries.firstOrNull { it.key == typeKey }
          ?: manifestError("Unknown setting type: $typeKey")
      val options = setting.optJSONArray("options").options()
      manifestCheck(type != ScriptSetting.Type.LIST || options.isNotEmpty()) {
        "The list setting $key needs options"
      }
      ScriptSetting(
        key = key,
        type = type,
        title = setting.requiredString("title"),
        summary = setting.optionalString("summary"),
        defaultJson =
          if (setting.has("default")) jsonOf(setting.get("default"))
          else defaultJson(type, options),
        options = options,
      )
    }
  }

  private fun defaultJson(type: ScriptSetting.Type, options: List<Pair<String, String>>): String =
    when (type) {
      ScriptSetting.Type.SWITCH -> "false"
      ScriptSetting.Type.LIST -> jsonOf(options.first().first)
      ScriptSetting.Type.TEXT -> jsonOf("")
      ScriptSetting.Type.NUMBER -> "0"
      ScriptSetting.Type.BUTTON -> "null"
    }

  private fun rules(array: JSONArray?): List<ScriptRule> {
    val rules = array.objects("rule", MAX_RULES).mapNotNull(::rule)
    manifestCheck(rules.count { it.match != null } <= MAX_MATCH_RULES) {
      "A script can have at most $MAX_MATCH_RULES rules with match"
    }
    return rules
  }

  private fun query(json: JSONObject, nested: Boolean = false): NodeQuery {
    json.unknownKeys(NodeQuery.KEYS).firstOrNull()?.let { throw UnsupportedField(it) }
    manifestCheck(!json.has("inside") || json.opt("inside") is JSONObject) {
      "inside is an object, like match"
    }
    manifestCheck(!nested || !json.has("inside")) { "inside can't hold another inside" }
    json.optJSONObject("inside")?.let { query(it, nested = true) }
    manifestCheck(!json.has("has") || json.opt("has") is JSONObject) { "has is an object, like match" }
    manifestCheck(!nested || !json.has("has")) { "has can't hold another has, and inside can't hold has" }
    json.optJSONObject("has")?.let {
      manifestCheck(!it.has("inside")) { "has can't hold inside" }
      query(it, nested = true)
    }
    val query = NodeQuery.of(json)
    manifestCheck(query != NodeQuery()) { "A match needs at least one field" }
    checkRole(query.role)
    return query
  }

  private fun checkRole(role: String?) =
    manifestCheck(role == null || role in ScriptRoles.names) {
      "Unknown role $role. Roles: ${ScriptRoles.names.sorted().joinToString()}"
    }

  private fun itemActions(array: JSONArray?): List<RuleItemAction> =
    array.objects("action", MAX_RULE_ACTIONS).mapNotNull { unlessUnsupported { itemAction(it) } }

  private fun itemAction(json: JSONObject): RuleItemAction? {
    val verbs = RuleItemAction.Verb.entries
    val unknown = json.unknownKeys(verbs.map { it.key }.toSet() + "title")
    unknown.forEach(::leaveOut)
    val title = json.optionalString("title")?.trim().orEmpty()
    manifestCheck(title.isNotEmpty()) { "An action needs a title" }
    if (unknown.isNotEmpty() && verbs.none { json.has(it.key) }) return null
    val verb =
      verbs.singleOrNull { json.has(it.key) }
        ?: manifestError("The action $title needs one of ${verbs.joinToString { it.key }}")
    val value = json.get(verb.key)
    if (verb != RuleItemAction.Verb.SPEAK) return RuleItemAction(title, verb, requiredTarget(value))
    return when (value) {
      is String -> RuleItemAction(title, verb, text = value)
      is JSONObject if value.keys().asSequence().toList() == listOf("textOf") ->
        RuleItemAction(title, verb, requiredTarget(value.get("textOf")))
      else -> manifestError("The action $title speaks text or { textOf: item }")
    }
  }

  private fun requiredTarget(value: Any?): NodeQuery =
    targetOf(value) ?: manifestError("An item is named by an id or a match")

  private fun targetOf(value: Any?): NodeQuery? =
    when (value) {
      null,
      JSONObject.NULL -> null
      is String -> NodeQuery(id = value.ifEmpty { manifestError("An item id can't be empty") })
      is JSONObject -> query(value)
      else -> manifestError("An item is named by an id or a match")
    }

  private fun longPressAfterOf(value: Any?): Int? =
    when (value) {
      null,
      JSONObject.NULL -> null
      else ->
        (value as? Number)
          ?.takeIf { it.toDouble() == it.toInt().toDouble() }
          ?.toInt()
          ?.takeIf { it in MIN_LONG_PRESS_AFTER..MAX_LONG_PRESS_AFTER }
          ?: manifestError(
            "longPressAfter is a number of milliseconds from $MIN_LONG_PRESS_AFTER to " +
              "$MAX_LONG_PRESS_AFTER"
          )
    }

  private fun hideOf(value: Any?, key: String): Hide =
    when (value) {
      null,
      JSONObject.NULL,
      false -> Hide.NONE
      true -> Hide.SELF
      "all" -> Hide.ALL
      else -> manifestError("$key is true, false or 'all'")
    }

  private fun commands(array: JSONArray?, permissions: Set<ScriptPermission>): List<ScriptCommand> {
    val list = array.objects("command", MAX_COMMANDS)
    manifestCheck(list.isEmpty() || ScriptPermission.INPUT in permissions) {
      "Commands need the input permission"
    }
    val ids = HashSet<String>()
    return list.map { command ->
      val id = uniqueKey(command.requiredString("id"), ids, "Command ids")
      val gestures = command.stringList("gesture").distinct()
      gestures
        .firstOrNull { !ScriptInput.isGesture(it) }
        ?.let { manifestError("Unknown gesture $it. docs/scripting.md lists the gestures.") }
      val voice = command.stringList("voice").distinct()
      ScriptCommand(
          id = id,
          title = command.requiredString("title"),
          gestures = gestures,
          keys =
            command.stringList("keys").distinct().map {
              asManifestError { ScriptInput.parseKeys(it) }
            },
          menu = command.optBoolean("menu", false),
          control = command.optBoolean("control", false),
          voice = voice,
        )
        .also {
          manifestCheck(it.bindings.isNotEmpty()) {
            "The command $id needs gesture, keys, menu or control, or voice"
          }
        }
    }
  }

  private fun navigation(
    array: JSONArray?,
    permissions: Set<ScriptPermission>,
  ): List<ScriptNavigation> {
    val list = array.objects("navigation item", MAX_NAVIGATION)
    manifestCheck(list.isEmpty() || ScriptPermission.INPUT in permissions) {
      "navigation needs the input permission"
    }
    return list.mapNotNull { json ->
      json.unknownKeys(NAVIGATION_KEYS).forEach(::leaveOut)
      val title = json.requiredString("title")
      val match =
        unlessUnsupported { json.optJSONObject("match")?.let { query(it) } ?: NodeQuery() }
          ?: return@mapNotNull null
      val query = json.optionalString("id")?.let { match.copy(id = it) } ?: match
      manifestCheck(query != NodeQuery()) { "The navigation item $title needs id or match" }
      ScriptNavigation(title, query)
    }
  }

  private fun uniqueKey(key: String, seen: MutableSet<String>, what: String): String =
    key.also {
      manifestCheck(KEY_PATTERN.matches(it) && seen.add(it)) {
        "$what must be unique letters, digits, . - or _: $it"
      }
    }

  private fun packageName(name: String): String =
    name.also { manifestCheck(it.isNotBlank() && ' ' !in it) { "Not a package name: $it" } }

  private fun JSONObject.unknownKeys(known: Set<String>): List<String> =
    keys().asSequence().filterNot { it in known }.toList()

  private fun leaveOut(field: String) {
    unsupported += field
    leftOut++
  }

  /** Returns what [parse] gives, or null if a match in it has an unsupported field. */
  private inline fun <T : Any> unlessUnsupported(parse: () -> T?): T? =
    try {
      parse()
    } catch (e: UnsupportedField) {
      leaveOut(e.field)
      null
    }

  private fun JSONObject.requiredString(key: String): String =
    optionalString(key)?.takeIf { it.isNotBlank() } ?: manifestError("The manifest needs $key")

  private fun JSONObject.optionalString(key: String): String? =
    if (has(key) && !isNull(key)) get(key).toString() else null

  private fun JSONObject.stringList(key: String): List<String> =
    when (val value = opt(key)) {
      null,
      JSONObject.NULL -> emptyList()
      is String -> listOf(value)
      is JSONArray -> value.items().map { it as? String ?: notStrings(key) }
      else -> notStrings(key)
    }

  private fun notStrings(key: String): Nothing =
    manifestError("$key must be a string or a list of strings")

  private fun JSONArray?.objects(what: String, max: Int = Int.MAX_VALUE): List<JSONObject> {
    val items = items()
    manifestCheck(items.size <= max) { "At most $max ${what}s" }
    return items.map { it as? JSONObject ?: manifestError("Each $what must be an object") }
  }

  private companion object {
    val ID_PATTERN = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
    val KEY_PATTERN = Regex("^[A-Za-z0-9_.-]{1,64}$")
    val HOMEPAGE_PATTERN = Regex("^https?://[^\\s/?#]+\\S*$", RegexOption.IGNORE_CASE)
    const val MAX_RULES = 500
    const val MAX_MATCH_RULES = 100
    const val MAX_RULE_ACTIONS = 10
    const val MAX_COMMANDS = 50
    const val MAX_NAVIGATION = 20
    const val MIN_LONG_PRESS_AFTER = 100
    const val MAX_LONG_PRESS_AFTER = 10_000
    val NAVIGATION_KEYS = setOf("title", "id", "match")
    val RULE_KEYS =
      setOf(
        "id",
        "match",
        "window",
        "activity",
        "label",
        "speak",
        "hide",
        "silence",
        "role",
        "state",
        "hint",
        "heading",
        "group",
        "readBefore",
        "readAfter",
        "actions",
        "activate",
        "liftToActivate",
        "longPressAfter",
      )
  }
}
