package dev.greeny.kmr.language.editor;

import com.intellij.codeInsight.AutoPopupController;
import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.completion.InsertionContext;
import com.intellij.codeInsight.completion.PrioritizedLookupElement;
import com.intellij.codeInsight.completion.impl.CamelHumpMatcher;
import com.intellij.codeInsight.completion.PrefixMatcher;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.util.ProcessingContext;
import dev.greeny.kmr.language.KmrPascalIcons;
import dev.greeny.kmr.language.KmrPascalLanguage;
import dev.greeny.kmr.language.psi.KmrPascalDocComment;
import dev.greeny.kmr.language.psi.KmrPascalNamedElement;
import dev.greeny.kmr.language.psi.KmrPascalRoutineDeclaration;
import dev.greeny.kmr.language.psi.KmrPascalTokenSets;
import dev.greeny.kmr.language.resolve.KmrPascalCallable;
import dev.greeny.kmr.language.stubs.KmrPascalEvent;
import dev.greeny.kmr.language.stubs.KmrPascalEvents;
import dev.greeny.kmr.language.stubs.KmrPascalStubScope;
import dev.greeny.kmr.language.unit.KmrPascalCompilationUnit;
import dev.greeny.kmr.language.unit.KmrPascalDirective;
import dev.greeny.kmr.language.unit.KmrPascalResolveContextService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Completion inside {@code {$...}} directives: the directive names the game's preprocessor knows, the events of the
 * target version in {@code {$EVENT}} and, after the ":", the procedures of the compilation unit that can handle the
 * event. Directives are comment tokens, so nothing of the ordinary completion applies here.
 */
public class KmrPascalDirectiveCompletionContributor extends CompletionContributor
{

	private static final double PRIORITY_MATCHING_HANDLER = 20;
	private static final double PRIORITY_OTHER_HANDLER = 10;

	public KmrPascalDirectiveCompletionContributor()
	{
		extend(CompletionType.BASIC, PlatformPatterns.psiComment().withLanguage(KmrPascalLanguage.INSTANCE), new CompletionProvider<>()
		{
			@Override
			protected void addCompletions(@NotNull CompletionParameters parameters, @NotNull ProcessingContext context, @NotNull CompletionResultSet result)
			{
				complete(parameters, result);
			}
		});
	}

	private static void complete(@NotNull CompletionParameters parameters, @NotNull CompletionResultSet result)
	{
		PsiElement position = parameters.getPosition();
		if (!KmrPascalTokenSets.DIRECTIVES.contains(position.getNode().getElementType())) {
			return;
		}
		// the text is the one of the copy completion works on, so everything up to the caret is what the user typed
		String text = position.getText();
		int caret = parameters.getOffset() - position.getTextRange().getStartOffset();
		if (caret < 2 || caret > text.length() || !text.startsWith("{$")) {
			return;
		}
		int nameEnd = 2;
		while (nameEnd < text.length() && isNamePart(text.charAt(nameEnd))) {
			nameEnd++;
		}
		if (caret <= nameEnd) {
			addDirectiveNames(text.substring(2, caret), result);
			result.stopHere();
			return;
		}
		if (!"EVENT".equalsIgnoreCase(text.substring(2, nameEnd))) {
			return;
		}
		int argument = nameEnd;
		while (argument < text.length() && (text.charAt(argument) == ' ' || text.charAt(argument) == '\t')) {
			argument++;
		}
		if (caret < argument) {
			return;
		}
		PsiFile file = position.getContainingFile().getOriginalFile();
		int colon = text.indexOf(':', argument);
		if (colon >= 0 && caret > colon) {
			addHandlers(file, position.getTextRange().getStartOffset(), text.substring(argument, colon).trim(), prefix(text, colon + 1, caret), result);
		} else {
			addEvents(file, prefix(text, argument, caret), result);
		}
		result.stopHere();
	}

	// ---------------------------------------------------------------------------------------------------------------

	private static void addDirectiveNames(@NotNull String prefix, @NotNull CompletionResultSet result)
	{
		// directive names are written in capitals, where a camel hump matcher would see a word start in every letter
		CompletionResultSet names = result.withPrefixMatcher(new IgnoreCasePrefixMatcher(prefix));
		for (KmrPascalDirective.Known known : KmrPascalDirective.KNOWN) {
			names.addElement(LookupElementBuilder.create(known.name)
				.withIcon(AllIcons.Nodes.Annotationtype)
				.withTailText(known.hasArgument() ? " " + known.argument : "", true)
				.withTypeText(known.description, true)
				.withInsertHandler((context, item) -> finish(context, known.hasArgument() ? " " : null)));
		}
	}

	private static void addEvents(@NotNull PsiFile file, @NotNull String prefix, @NotNull CompletionResultSet result)
	{
		CompletionResultSet events = withPrefix(result, prefix);
		String version = KmrPascalStubScope.getInstance(file.getProject()).versionFor(file);
		for (KmrPascalEvent event : KmrPascalEvents.getInstance(file.getProject()).getEvents(version)) {
			KmrPascalDocComment doc = event.doc;
			events.addElement(LookupElementBuilder.create(event.field, event.eventName)
				.withIcon(KmrPascalIcons.EVENT_HANDLER)
				.withTailText(event.getSignature().getSignatureText(), true)
				.withTypeText("handled by " + event.handlerName, true)
				.withStrikeoutness(doc != null && doc.hasTag(KmrPascalDocComment.TAG_DEPRECATED))
				.withInsertHandler((context, item) -> {
					finish(context, ":");
					AutoPopupController.getInstance(context.getProject()).scheduleAutoPopup(context.getEditor());
				}));
		}
	}

	/**
	 * Procedures of the unit that may be registered for the event, those with its signature first. A procedure that
	 * already handles an event - named like one, or named by another {$EVENT} - is left out: the game refuses to
	 * register the same procedure twice.
	 */
	private static void addHandlers(@NotNull PsiFile file, int directiveStart, @NotNull String eventName, @NotNull String prefix, @NotNull CompletionResultSet result)
	{
		CompletionResultSet handlers = withPrefix(result, prefix);
		KmrPascalEvents events = KmrPascalEvents.getInstance(file.getProject());
		String version = KmrPascalStubScope.getInstance(file.getProject()).versionFor(file);
		KmrPascalEvent event = events.findByEventName(version, eventName);
		KmrPascalCompilationUnit unit = KmrPascalResolveContextService.getInstance(file.getProject()).getUnitFor(file);
		Set<String> taken = new HashSet<>();
		for (KmrPascalEvents.Registration registration : KmrPascalEvents.registrations(unit)) {
			// not the directive being edited: its own handler must stay completable
			if (registration.comment.getTextRange().getStartOffset() != directiveStart
				|| !registration.comment.getContainingFile().isEquivalentTo(file)) {
				taken.add(registration.handlerName.toLowerCase(Locale.ROOT));
			}
		}
		for (KmrPascalRoutineDeclaration routine : procedures(unit)) {
			String name = routine.getName();
			if (name == null || taken.contains(name.toLowerCase(Locale.ROOT)) || events.findByHandlerName(version, name) != null) {
				continue;
			}
			KmrPascalCallable signature = KmrPascalCallable.of(routine);
			if (signature == null || signature.isFunction()) {
				continue;
			}
			boolean matches = event != null && event.accepts(signature);
			handlers.addElement(PrioritizedLookupElement.withPriority(
				LookupElementBuilder.create(routine, name)
					.withIcon(KmrPascalIcons.forDeclaration(routine))
					.withTailText(signature.getSignatureText(), true)
					.withTypeText(matches ? "matching handler" : "procedure", true)
					.withInsertHandler((context, item) -> finish(context, null)),
				matches ? PRIORITY_MATCHING_HANDLER : PRIORITY_OTHER_HANDLER));
		}
	}

	@NotNull
	private static List<KmrPascalRoutineDeclaration> procedures(@NotNull KmrPascalCompilationUnit unit)
	{
		List<KmrPascalRoutineDeclaration> result = new ArrayList<>();
		for (List<KmrPascalNamedElement> group : unit.getGlobals().values()) {
			for (KmrPascalNamedElement global : group) {
				if (global instanceof KmrPascalRoutineDeclaration) {
					result.add((KmrPascalRoutineDeclaration) global);
				}
			}
		}
		return result;
	}

	// ---------------------------------------------------------------------------------------------------------------

	/**
	 * Places the caret after the inserted name and keeps the directive well formed: a separator (the space before an
	 * argument, the ":" of {$EVENT}) is added unless it is already there, and a missing "}" closes the directive.
	 * Without a separator the caret ends up behind the "}".
	 */
	private static void finish(@NotNull InsertionContext context, @Nullable String separator)
	{
		Document document = context.getDocument();
		CharSequence text = document.getCharsSequence();
		int tail = context.getTailOffset();
		int lineEnd = document.getLineEndOffset(document.getLineNumber(tail));
		int closing = -1;
		for (int i = tail; i < lineEnd; i++) {
			if (text.charAt(i) == '}') {
				closing = i;
				break;
			}
		}
		int caret = tail;
		if (separator != null) {
			if (tail < lineEnd && text.charAt(tail) == separator.charAt(0)) {
				caret = tail + 1;
			} else {
				document.insertString(tail, separator);
				caret = tail + separator.length();
				if (closing >= 0) {
					closing += separator.length();
				}
			}
		}
		if (closing < 0) {
			document.insertString(caret, "}");
			closing = caret;
		}
		context.getEditor().getCaretModel().moveToOffset(separator == null ? closing + 1 : caret);
		context.commitDocument();
	}

	/** Pascal is case-insensitive, and the prefix of a directive is not what the platform would guess in a comment. */
	@NotNull
	private static CompletionResultSet withPrefix(@NotNull CompletionResultSet result, @NotNull String prefix)
	{
		return result.withPrefixMatcher(new CamelHumpMatcher(prefix, false));
	}

	private static final class IgnoreCasePrefixMatcher extends PrefixMatcher
	{
		IgnoreCasePrefixMatcher(@NotNull String prefix)
		{
			super(prefix);
		}

		@Override
		public boolean prefixMatches(@NotNull String name)
		{
			return StringUtil.startsWithIgnoreCase(name, getPrefix());
		}

		@Override
		public @NotNull PrefixMatcher cloneWithPrefix(@NotNull String prefix)
		{
			return new IgnoreCasePrefixMatcher(prefix);
		}
	}

	@NotNull
	private static String prefix(@NotNull String text, int start, int caret)
	{
		int from = start;
		while (from < caret && Character.isWhitespace(text.charAt(from))) {
			from++;
		}
		return text.substring(from, caret);
	}

	private static boolean isNamePart(char c)
	{
		return Character.isLetterOrDigit(c) || c == '_';
	}

}
