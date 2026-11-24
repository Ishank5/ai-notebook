package com.humblesolutions.ainotetaker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material3.Text
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.humblesolutions.ainotetaker.ui.theme.Tas
import com.humblesolutions.ainotetaker.ui.theme.TasShape

/**
 * A labelled field.
 *
 * THE LABEL IS ALWAYS ABOVE, never a floating placeholder. A placeholder that
 * doubles as a label disappears the moment somebody types, so a half-filled
 * twenty-five-field form becomes a column of anonymous boxes — and these forms
 * are filled in on a doorstep, interrupted, and come back to.
 *
 * The required marker is a red asterisk AFTER the label, matching the web.
 */
@Composable
fun TasField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    required: Boolean = false,
    placeholder: String? = null,
    hint: String? = null,
    errorText: String? = null,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    /**
     * What the keyboard does when [imeAction] is tapped.
     *
     * WITHOUT THIS THE KEY IS DECORATION. Compose draws "Next" or "Done" from
     * [imeAction] alone and then does nothing when it is pressed, so a form
     * whose only way forward is a button below the keyboard leaves the key
     * sitting there doing nothing.
     */
    onImeAction: (() -> Unit)? = null,
    /**
     * A SUGGESTION, NOT A RULE: this sets the keyboard's shift key for the
     * first letter and nothing more. Somebody typing an all-lowercase venue
     * name still gets exactly what they typed — nothing here rewrites a value.
     *
     * OFF BY DEFAULT, and deliberately not inferred from [keyboardType]. Plenty
     * of plain text fields must never be capitalised — the username box holds
     * `first.last` — so a clever default would quietly break the ones nobody
     * thought to check. The screens that want it ask for it.
     */
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    /** Notes and addresses wrap; a name does not. */
    singleLine: Boolean = true,
    minLines: Int = 1,
    isPassword: Boolean = false,
    prefix: String? = null,
    maxLength: Int? = null,
    /**
     * Fired when the field LOSES focus.
     *
     * The duplicate-phone check hangs off this: a query per keystroke is a
     * read per keystroke, and the answer only matters once the number is
     * whole. Without it the check never runs at all.
     */
    onBlur: (() -> Unit)? = null,
    /**
     * How many lines before it scrolls. One, unless the value is prose.
     *
     * A DRAFTED COMMENT IS PROSE, and a single-line box truncated it to
     * "Love that it covers everything from writing to…" — the exact text the
     * viewer is supposed to read, edit and copy, unreadable in the one control
     * that exists to show it.
     */
    maxLines: Int = 1,
    /**
     * Render with the SYSTEM font rather than the brand one.
     *
     * FOR TEXT THAT LEGITIMATELY CONTAINS EMOJI. The app bundles Archivo and
     * Noto Sans; neither carries emoji, and Compose does not fall back to the
     * system emoji font for a `FontFamily` built from bundled resources. So a
     * comment ending in 🙌 rendered as a box — in the one control whose entire
     * job is to show somebody the text they are about to paste.
     *
     * The typeface differs slightly for that field. A box does not.
     */
    systemFont: Boolean = false,
    /**
     * Shows a masked field in the clear.
     *
     * A PASSWORD BOX WITH NO WAY TO READ IT BACK is a guess typed in sunlight
     * with a thumb, and the app's minimum is eight characters. The caller owns
     * the flag so it can be reset on every failure rather than left on.
     */
    passwordVisible: Boolean = false,
    /**
     * What the password manager should offer here.
     *
     * WITHOUT IT THE KEYBOARD OFFERS NOTHING. Android matches a saved
     * credential by the content type, so a sign-in screen that declares none
     * makes every worker type a username they have saved — on the one screen
     * where getting it wrong locks them out.
     */
    contentType: ContentType? = null,
    /**
     * A glyph inside the box, at the leading edge.
     *
     * NOT THE SAME JOB AS [prefix], which is a boxed-off `+91` sitting beside
     * the field as its own control. This is decoration inside the same box —
     * the person and the padlock on the sign-in screen — and it never takes a
     * tap.
     */
    leading: (@Composable () -> Unit)? = null,
    /** A control inside the box, at the trailing edge. The reveal eye is one. */
    trailing: (@Composable () -> Unit)? = null,
    /** How the text is drawn, when not as typed — an Aadhaar number in fours. */
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val colors = Tas.colors
    var focused by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = buildAnnotatedString {
                append(label)
                if (required) {
                    append("  ")
                    withStyle(SpanStyle(color = colors.destructive)) { append("*") }
                }
            },
            style = Tas.type.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
            color = colors.mutedForeground,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (prefix != null) {
                Box(
                    modifier = Modifier
                        .defaultMinSize(minHeight = TasShape.tap)
                        .background(colors.muted, RoundedCornerShape(TasShape.radius))
                        .border(1.dp, colors.input, RoundedCornerShape(TasShape.radius))
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(prefix, style = Tas.type.body, color = colors.mutedForeground)
                }
            }

            val selection = TextSelectionColors(
                handleColor = colors.primary,
                backgroundColor = colors.primary.copy(alpha = 0.25f),
            )
            CompositionLocalProvider(LocalTextSelectionColors provides selection) {
                BasicTextField(
                    value = value,
                    onValueChange = { next ->
                        onValueChange(if (maxLength != null) next.take(maxLength) else next)
                    },
                    enabled = enabled,
                    /*
                     * BOTH SIDES OF THIS FIELD'S LINE BEHAVIOUR, reconciled.
                     * `maxLines`/`systemFont` came from the updates work;
                     * `singleLine`/`minLines` from the event wizard's notes
                     * box. Passed through raw they can contradict each other —
                     * the notes field asks for three minimum lines while
                     * maxLines still defaults to one, and Compose THROWS when
                     * minLines exceeds maxLines. So the pair is normalised
                     * here rather than trusted: one line wins only if both
                     * agree on it, and the ceiling is never below the floor.
                     */
                    singleLine = singleLine && maxLines <= 1,
                    minLines = minLines,
                    maxLines = if (singleLine && maxLines <= 1) 1 else maxOf(maxLines, minLines),
                    textStyle = Tas.type.body.copy(
                        color = colors.foreground,
                        fontFamily = if (systemFont) {
                            androidx.compose.ui.text.font.FontFamily.Default
                        } else Tas.type.body.fontFamily,
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.primary),
                    visualTransformation =
                        if (isPassword && !passwordVisible) PasswordVisualTransformation()
                        else visualTransformation,
                    /*
                     * A PASSWORD IS NOT PROSE, and the keyboard has to be told
                     * so. `isPassword` alone only masks the glyphs on screen —
                     * the IME still sees an ordinary text field, offers
                     * suggestions, autocorrects, and appends a space when one
                     * is accepted. The password that leaves the field is then
                     * not the one that was typed, and Firebase answers with
                     * "username or password incorrect" about a password the
                     * person entered perfectly. iOS never had this because
                     * SecureField suppresses all of it by nature.
                     */
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (isPassword) KeyboardType.Password else keyboardType,
                        imeAction = imeAction,
                        capitalization =
                            if (isPassword) KeyboardCapitalization.None else capitalization,
                        autoCorrectEnabled = !isPassword,
                    ),
                    /*
                     * Every action mapped to the same callback. Which one
                     * fires depends on [imeAction], and a `when` here would
                     * only be a second place for the two to disagree.
                     */
                    keyboardActions = KeyboardActions(
                        onNext = { onImeAction?.invoke() },
                        onDone = { onImeAction?.invoke() },
                        onGo = { onImeAction?.invoke() },
                        onSearch = { onImeAction?.invoke() },
                        onSend = { onImeAction?.invoke() },
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (contentType != null) {
                                Modifier.semantics { this.contentType = contentType }
                            } else Modifier,
                        )
                        .defaultMinSize(minHeight = TasShape.button)
                        .background(if (focused) colors.card else colors.inputBackground, RoundedCornerShape(TasShape.radius))
                        .border(
                            width = if (focused || errorText != null) 1.5.dp else 1.dp,
                            color = when {
                                errorText != null -> colors.destructive
                                focused -> colors.ring
                                else -> colors.inputBackground
                            },
                            shape = RoundedCornerShape(TasShape.radius),
                        )
                        .onFocusChanged { state ->
                            // Falling edge only — `onFocusChanged` also fires
                            // on gaining focus, and checking then would query
                            // for a number nobody has typed yet.
                            if (focused && !state.isFocused) onBlur?.invoke()
                            focused = state.isFocused
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    decorationBox = { inner ->
                        /*
                         * CENTRED ON ONE LINE, TOP-ALIGNED ON SEVERAL.
                         *
                         * Vertical centring is right for a one-line box and
                         * wrong for every taller one. The notes field is three
                         * lines high from the start, so a centred caret opens
                         * halfway down an empty box, the first line of typing
                         * sits in the middle of nowhere, and the text grows
                         * outward from the centre instead of down from the
                         * top. The placeholder was centred by the same rule
                         * while the typed text was not, so the cursor appeared
                         * to jump the moment somebody started writing.
                         *
                         * Uses the same normalised one-line test as the field
                         * itself, so the two cannot disagree about what counts
                         * as single-line.
                         */
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = if (singleLine && maxLines <= 1) Alignment.CenterStart else Alignment.TopStart,
                        ) {
                            if (leading != null) {
                                Box(modifier = Modifier.align(Alignment.CenterStart)) { leading() }
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // Room for the controls, so a long value
                                    // does not slide underneath either of them.
                                    .padding(
                                        start = if (leading != null) 34.dp else 0.dp,
                                        end = if (trailing != null) 32.dp else 0.dp,
                                    ),
                                contentAlignment =
                                    if (singleLine && maxLines <= 1) Alignment.CenterStart
                                    else Alignment.TopStart,
                            ) {
                                if (value.isEmpty() && placeholder != null) {
                                    Text(
                                        placeholder,
                                        style = Tas.type.body,
                                        color = colors.faintForeground,
                                    )
                                }
                                inner()
                            }
                            if (trailing != null) {
                                Box(modifier = Modifier.align(Alignment.CenterEnd)) { trailing() }
                            }
                        }
                    },
                )
            }
        }

        // The error replaces the hint rather than stacking under it: two lines
        // of small text under a field is where people stop reading.
        when {
            errorText != null ->
                Text(errorText, style = Tas.type.caption, color = colors.destructive)
            hint != null ->
                Text(hint, style = Tas.type.caption, color = colors.mutedForeground)
        }
    }
}
