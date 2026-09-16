package dev.greeny.kmr.language.inspections;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiReference;
import dev.greeny.kmr.language.editor.KmrPascalEventDirectiveReference;
import dev.greeny.kmr.language.psi.KmrPascalDocComment;
import dev.greeny.kmr.language.psi.KmrPascalReferenceElement;
import dev.greeny.kmr.language.psi.KmrPascalRoutineDeclaration;
import dev.greeny.kmr.language.stubs.KmrPascalEvent;
import dev.greeny.kmr.language.stubs.KmrPascalEvents;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Uses of declarations carrying a {@code @deprecated} doc tag (API members marked in the stubs, or user code), and
 * handlers of deprecated events: a routine with the event's conventional name, or a {@code {$EVENT}} directive naming
 * the event. Calls and handlers the plugin can migrate to the {@code ...Ex} variant on its own get a fix.
 */
public class KmrPascalDeprecatedInspection extends LocalInspectionTool
{

	@Override
	public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly)
	{
		return new PsiElementVisitor()
		{
			@Override
			public void visitElement(@NotNull PsiElement element)
			{
				if (element instanceof KmrPascalReferenceElement reference) {
					checkReference(reference, holder);
				} else if (element instanceof KmrPascalRoutineDeclaration routine) {
					checkHandler(routine, holder);
				} else if (element instanceof PsiComment comment) {
					checkDirective(comment, holder);
				}
			}
		};
	}

	private static void checkReference(@NotNull KmrPascalReferenceElement reference, @NotNull ProblemsHolder holder)
	{
		if (KmrPascalInspectionUtil.shouldSkip(reference)) {
			return;
		}
		PsiElement target = KmrPascalInspectionUtil.resolveSingle(reference);
		KmrPascalDocComment doc = target == null ? null : KmrPascalDocComment.of(target);
		if (doc == null || !doc.hasTag(KmrPascalDocComment.TAG_DEPRECATED)) {
			return;
		}
		KmrPascalLegacyCallRewriter.Plan plan = KmrPascalLegacyCallRewriter.plan(reference);
		LocalQuickFix[] fixes = plan == null ? LocalQuickFix.EMPTY_ARRAY : new LocalQuickFix[]{new KmrPascalLegacyCallRewriter.Fix(plan.newName())};
		KmrPascalInspectionUtil.report(holder, reference, reference.getNameIdentifier().getTextRangeInParent(),
			message(reference.getReferenceName(), doc.getTagValue(KmrPascalDocComment.TAG_DEPRECATED)), ProblemHighlightType.LIKE_DEPRECATED, fixes);
	}

	/** A routine named like a deprecated event (handlers registered by directive are reported at the directive). */
	private static void checkHandler(@NotNull KmrPascalRoutineDeclaration routine, @NotNull ProblemsHolder holder)
	{
		String name = routine.getName();
		PsiElement identifier = routine.getNameIdentifier();
		if (name == null || identifier == null || KmrPascalInspectionUtil.shouldSkip(routine)) {
			return;
		}
		PsiFile file = routine.getContainingFile();
		KmrPascalEvent event = KmrPascalEvents.getInstance(file.getProject()).findByHandlerName(KmrPascalInspectionUtil.versionOf(file), name);
		String deprecation = deprecationOf(event);
		if (deprecation == null) {
			return;
		}
		KmrPascalInspectionUtil.report(holder, identifier, com.intellij.openapi.util.TextRange.from(0, identifier.getTextLength()), message(name, deprecation), ProblemHighlightType.LIKE_DEPRECATED,
			handlerFixes(routine, event));
	}

	/** {@code {$EVENT evtX:Handler}} naming a deprecated event. */
	private static void checkDirective(@NotNull PsiComment comment, @NotNull ProblemsHolder holder)
	{
		for (PsiReference reference : comment.getReferences()) {
			if (!(reference instanceof KmrPascalEventDirectiveReference directiveReference) || directiveReference.isHandlerPart()) {
				continue;
			}
			if (KmrPascalInspectionUtil.shouldSkip(comment)) {
				return;
			}
			PsiFile file = comment.getContainingFile();
			KmrPascalEvents events = KmrPascalEvents.getInstance(file.getProject());
			String version = KmrPascalInspectionUtil.versionOf(file);
			KmrPascalEvent event = events.findByEventName(version, directiveReference.getName());
			String deprecation = deprecationOf(event);
			if (deprecation == null) {
				continue;
			}
			// the message names the Ex handler; the directive wants the Ex event name
			KmrPascalEvent replacement = KmrPascalLegacyEventRewriter.replacementOf(event, events, version);
			String text = replacement == null ? deprecation : "Use " + replacement.eventName + " instead";
			KmrPascalRoutineDeclaration routine = KmrPascalLegacyEventRewriter.routineOf(comment);
			KmrPascalInspectionUtil.report(holder, comment, reference.getRangeInElement(), message(directiveReference.getName(), text), ProblemHighlightType.LIKE_DEPRECATED,
				routine == null ? LocalQuickFix.EMPTY_ARRAY : handlerFixes(routine, event));
		}
	}

	@Nullable
	private static String deprecationOf(@Nullable KmrPascalEvent event)
	{
		return event == null || event.doc == null || !event.doc.hasTag(KmrPascalDocComment.TAG_DEPRECATED) ? null : event.doc.getTagValue(KmrPascalDocComment.TAG_DEPRECATED);
	}

	@NotNull
	private static LocalQuickFix[] handlerFixes(@NotNull KmrPascalRoutineDeclaration routine, @NotNull KmrPascalEvent event)
	{
		KmrPascalLegacyEventRewriter.Plan plan = KmrPascalLegacyEventRewriter.plan(routine, event);
		return plan == null ? LocalQuickFix.EMPTY_ARRAY : new LocalQuickFix[]{new KmrPascalLegacyEventRewriter.Fix(plan.newName())};
	}

	@NotNull
	private static String message(@NotNull String name, @Nullable String deprecation)
	{
		return "'" + name + "' is deprecated" + (deprecation == null || deprecation.isEmpty() ? "" : ": " + deprecation);
	}

}
