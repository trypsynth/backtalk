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
 * License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.talkback.actor.gemini

import android.util.Base64
import com.google.android.accessibility.gemineye.api.AccessibilityTree
import com.google.android.accessibility.gemineye.screenoverview.json.MessageType
import com.google.android.accessibility.gemineye.screenoverview.json.ParseException
import com.google.android.accessibility.gemineye.screenoverview.json.Parser
import com.google.android.accessibility.talkback.actor.gemini.DataFieldUtils.GeminiResponse
import com.google.android.accessibility.talkback.actor.gemini.GeminiActor.ErrorReason
import com.google.android.accessibility.talkback.actor.gemini.GeminiActor.FinishReason
import com.google.android.accessibility.talkback.actor.gemini.GeminiRestRequestPerformer.GeminiRestResponseCallback
import com.google.android.accessibility.talkback.actor.gemini.screenqa.OverviewResponse
import com.google.android.accessibility.talkback.actor.gemini.screenqa.ScreenTree
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sends screen overview and screen question requests to the Gemini REST API. Google's release
 * only supports these through its own server, so this builds the prompts and parses the JSON reply
 * into the types that the screen Q&A UI already uses.
 */
internal class ScreenOverviewRequester(
  private val performRequest: (JSONObject, GeminiRestResponseCallback) -> Unit
) {
  private val parser = Parser()

  fun requestOverview(command: GeminiCommand.ScreenOverview, safetySettings: JSONArray) {
    val tree = ScreenTree.fromNode(command.focusedNode)
    val prompt = "$OVERVIEW_INSTRUCTIONS\n${languageInstruction()}\n\nNodes:\n${tree.description}"
    send(prompt, command.screenshot, safetySettings, command.listener) { json ->
      OverviewResponse.Success(parser.parseScreenOverview(json), tree, command.screenshot)
    }
  }

  fun requestQuery(command: GeminiCommand.ScreenQuery, safetySettings: JSONArray) {
    val history =
      command.chatHistory.orEmpty().joinToString(separator = "\n") {
        val speaker = if (it.messageType == MessageType.USER) "USER" else "MODEL"
        "$speaker: ${it.message}"
      }
    val prompt =
      "$QUERY_INSTRUCTIONS\n${languageInstruction()}\n\nNodes:\n${nodeList(command.a11yTree)}" +
        "\n\nConversation so far:\n$history\n\nQuestion: ${command.query}"
    send(prompt, command.screenshot, safetySettings, command.listener) { json ->
      OverviewResponse.QueryAnswer(
        parser.parseScreenQueryAnswer(json),
        command.a11yTree,
        command.screenshot,
      )
    }
  }

  private fun send(
    prompt: String,
    screenshot: ByteArray,
    safetySettings: JSONArray,
    listener: GeminiResponseCallback<OverviewResponse>,
    parse: (String) -> OverviewResponse,
  ) {
    if (screenshot.isEmpty()) {
      listener.onError(ErrorReason.NO_IMAGE)
      return
    }
    val postData =
      DataFieldUtils.createPostDataJson(
        prompt,
        Base64.encodeToString(screenshot, Base64.NO_WRAP),
        safetySettings,
      )
    postData.getJSONObject("generationConfig").put("responseMimeType", "application/json")
    performRequest(
      postData,
      object : GeminiRestResponseCallback {
        override fun onResponse(response: GeminiResponse) {
          val text = response.text()
          if (response.finishReason() != DataFieldUtils.FINISH_REASON_STOP || text == null) {
            listener.onResponse(FinishReason.ERROR_BLOCKED, error(FinishReason.ERROR_BLOCKED))
            return
          }
          try {
            listener.onResponse(FinishReason.STOP, parse(text))
          } catch (e: ParseException) {
            listener.onResponse(
              FinishReason.ERROR_PARSING_RESULT,
              error(FinishReason.ERROR_PARSING_RESULT),
            )
          }
        }

        override fun onFailure(reason: String) {
          listener.onResponse(FinishReason.ERROR_RESPONSE, error(FinishReason.ERROR_RESPONSE))
        }

        override fun onCancelled() {
          listener.onError(ErrorReason.JOB_CANCELLED)
        }
      },
    )
  }

  private fun error(finishReason: FinishReason) =
    OverviewResponse.Error(errorReason = null, finishReason = finishReason)

  private fun nodeList(tree: AccessibilityTree): String =
    (tree as? ScreenTree)?.description.orEmpty()

  private fun languageInstruction(): String =
    "Write all text in ${Locale.getDefault().getDisplayLanguage(Locale.ENGLISH)}. " +
      "Do not use markdown."

  companion object {
    private const val UI_ELEMENT_FORMAT =
      """Each UI element is an object with these fields:
- "label": the name of the control as shown on screen.
- "description": what the control does, in one short sentence.
- "type": one of button, floating_action_button, text_field, header, checkbox, menu, slider, tab, link.
- "parent_container": one of tab_bar, top_navigation, bottom_navigation, side_menu, filters, post, list_item, main_content.
- "potentiallyMatchingNodes": a list of objects like {"uniqueId": 12}, with the numbers of the nodes that match the control, most likely first."""

    private const val OVERVIEW_INSTRUCTIONS =
      """You help a blind person who uses the TalkBack screen reader understand their phone screen. You get a screenshot and a numbered list of the accessibility nodes on the screen.

Reply with only a JSON object with these fields:
- "summary": 2 to 4 sentences about what the screen is for and what is on it. Mention important content that the node list does not describe, such as images, charts, or text in images. Do not list every element.
- "top_images": up to 3 short descriptions of the most important images on the screen. Use an empty list if there are none.
- "top_ui_elements": up to 6 of the most useful controls on the screen.

$UI_ELEMENT_FORMAT"""

    private const val QUERY_INSTRUCTIONS =
      """You help a blind person who uses the TalkBack screen reader understand their phone screen. You get a screenshot, a numbered list of the accessibility nodes on the screen, the conversation so far, and a question.

Reply with only a JSON object with these fields:
- "answer": the answer to the question, in plain text.
- "ui_elements": the controls that the answer refers to, or an empty list.

$UI_ELEMENT_FORMAT"""
  }
}
