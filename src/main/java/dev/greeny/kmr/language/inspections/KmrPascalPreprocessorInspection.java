package dev.greeny.kmr.language.inspections;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiReference;
import com.intellij.psi.SyntaxTraverser;
import dev.greeny.kmr.language.editor.KmrPascalEventDirectiveReference;
import dev.greeny.kmr.language.psi.KmrPascalTypes;
import dev.greeny.kmr.language.stubs.KmrPascalEvent;
import dev.greeny.kmr.language.stubs.KmrPascalEvents;
import dev.greeny.kmr.language.stubs.KmrPascalStubLibrary;
import dev.greeny.kmr.language.unit.KmrPascalCompilationUnit;
import dev.greeny.kmr.language.unit.KmrPascalDirective;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Problems the preprocessor found while building the compilation unit the file is analysed in: missing or recursive
 * includes, a file included twice with active content, unbalanced {$IFDEF}/{$ENDIF}, {$ELSE}/{$ENDIF} without {$IF}.
 * Also {$EVENT} directives that name an unknown event, a procedure the unit does not declare (with a fix that creates
 * it), or a procedure that already handles an event - the game refuses to register the same procedure twice.
 */
public class KmrPascalPreprocessorInspection extends LocalInspectionTool
{

	@Override
	public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly)
	{
		return new PsiElementVisitor()
		{
			@Override
			public void visitElement(@NotNull PsiElement element)
			{
				if (element instanceof PsiFile && !KmrPascalStubLibrary.isStubFile((PsiFile) element)) {
					check((PsiFile) element, holder);
				}
			}
		};
	}

	private static void check(@NotNull PsiFile file, @NotNull ProblemsHolder holder)
	{
		KmrPascalCompilationUnit unit = KmrPascalInspectionUtil.unitOf(file);
		checkEventDirectives(file, unit, holder);
		for (KmrPascalCompilationUnit.Problem problem : unit.getProblems()) {
			if (!problem.file.isEquivalentTo(file.getOriginalFile()) || problem.range.isEmpty()) {
				continue;
			}
			PsiElement element = file.findElementAt(problem.range.getStartOffset());
			if (element == null) {
				continue;
			}
			ProblemHighlightType type = problem.kind == KmrPascalCompilationUnit.Problem.Kind.DUPLICATE_ACTIVE_INCLUDE
				? ProblemHighlightType.WARNING : ProblemHighlightType.GENERIC_ERROR;
			holder.registerProblem(element, problem.message, type);
		}
	}

	private static void checkEventDirectives(@NotNull PsiFile file, @NotNull KmrPascalCompilationUnit unit, @NotNull ProblemsHolder holder)
	{
		List<KmrPascalEvents.Registration> registrations = KmrPascalEvents.registrations(unit);
		for (PsiComment comment : SyntaxTraverser.psiTraverser(file).filter(PsiComment.class)) {
			if (comment.getNode().getElementType() != KmrPascalTypes.DIRECTIVE_EVENT || !unit.isActive(comment)) {
				continue;
			}
			for (PsiReference reference : comment.getReferences()) {
				if (!(reference instanceof KmrPascalEventDirectiveReference) || reference.resolve() != null) {
					continue;
				}
				KmrPascalEventDirectiveReference directiveReference = (KmrPascalEventDirectiveReference) reference;
				String name = directiveReference.getName();
				if (name.isEmpty()) {
					continue;
				}
				String message = directiveReference.isHandlerPart()
					? "Procedure '" + name + "' is not declared in the compilation unit"
					: "Unknown event '" + name + "'" + otherVersionsWithEvent(file, name);
				LocalQuickFix[] fixes = directiveReference.isHandlerPart() ? new LocalQuickFix[]{new CreateHandlerFix(name)} : LocalQuickFix.EMPTY_ARRAY;
				KmrPascalInspectionUtil.report(holder, comment, reference.getRangeInElement(), message, ProblemHighlightType.GENERIC_ERROR, fixes);
			}
			checkHandlerRegisteredOnce(file, unit, registrations, comment, holder);
		}
	}

	/**
	 * The game keeps one handler list per event and rejects a procedure that is already in it, so a procedure must be
	 * named by at most one {$EVENT} - and never by one naming the event it is the default handler of.
	 */
	private static void checkHandlerRegisteredOnce(@NotNull PsiFile file, @NotNull KmrPascalCompilationUnit unit,
												   @NotNull List<KmrPascalEvents.Registration> registrations,
												   @NotNull PsiComment comment, @NotNull ProblemsHolder holder)
	{
		KmrPascalDirective directive = KmrPascalDirective.of(comment);
		if (directive == null || directive.eventPart == null || directive.handlerPart == null || directive.handlerPart.name.isEmpty()) {
			return;
		}
		String handler = directive.handlerPart.name;
		KmrPascalEvent event = KmrPascalEvents.getInstance(file.getProject()).findByEventName(KmrPascalInspectionUtil.versionOf(file), directive.eventPart.name);
		if (event != null && event.handlerName.equalsIgnoreCase(handler)) {
			KmrPascalInspectionUtil.report(holder, comment, directive.handlerPart.range,
				"'" + handler + "' is the default handler of '" + event.eventName + "' and is registered by the game itself",
				ProblemHighlightType.GENERIC_ERROR);
			return;
		}
		for (KmrPascalEvents.Registration registration : registrations) {
			if (isSame(registration, file, comment)) {
				return; // only the directives before this one, so the first registration is not reported
			}
			if (!registration.handlerName.equalsIgnoreCase(handler) || !unit.isActive(registration.comment)) {
				continue;
			}
			boolean sameEvent = registration.eventName.equalsIgnoreCase(directive.eventPart.name);
			String message = sameEvent
				? "Event '" + registration.eventName + "' already has the handler '" + handler + "'"
				: "Procedure '" + handler + "' is already the handler of '" + registration.eventName + "'";
			KmrPascalInspectionUtil.report(holder, comment, directive.handlerPart.range, message,
				sameEvent ? ProblemHighlightType.GENERIC_ERROR : ProblemHighlightType.WARNING);
			return;
		}
	}

	private static boolean isSame(@NotNull KmrPascalEvents.Registration registration, @NotNull PsiFile file, @NotNull PsiComment comment)
	{
		return registration.comment.getTextRange().getStartOffset() == comment.getTextRange().getStartOffset()
			&& registration.comment.getContainingFile().isEquivalentTo(file);
	}

	@NotNull
	private static String otherVersionsWithEvent(@NotNull PsiFile file, @NotNull String eventName)
	{
		String current = KmrPascalInspectionUtil.versionOf(file);
		KmrPascalEvents events = KmrPascalEvents.getInstance(file.getProject());
		List<String> versions = new ArrayList<>();
		for (String version : KmrPascalStubLibrary.VERSIONS) {
			if (!version.equals(current) && events.findByEventName(version, eventName) != null) {
				versions.add(version);
			}
		}
		return versions.isEmpty() ? "" : " (available in game version " + String.join(", ", versions) + ")";
	}


	/** Adds the missing handler procedure, with the signature of the event the directive names, at the end of the file. */
	private static final class CreateHandlerFix implements LocalQuickFix
	{
		private final String name;

		CreateHandlerFix(@NotNull String name)
		{
			this.name = name;
		}

		@Override
		public @NotNull String getFamilyName()
		{
			return "Create procedure '" + name + "'";
		}

		@Override
		public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor)
		{
			PsiElement comment = descriptor.getPsiElement();
			PsiFile file = comment == null ? null : comment.getContainingFile();
			KmrPascalDirective directive = comment == null ? null : KmrPascalDirective.of(comment);
			Document document = file == null ? null : PsiDocumentManager.getInstance(project).getDocument(file);
			if (directive == null || directive.handlerPart == null || document == null) {
				return;
			}
			// plain types: the header must compile, so the kind words are left out
			KmrPascalEvent event = eventOf(file, directive);
			String header = "procedure " + directive.handlerPart.name + (event == null ? "" : event.getSignature().getSignatureText(false)) + ";";
			String text = (document.getTextLength() > 0 && document.getCharsSequence().charAt(document.getTextLength() - 1) != '\n' ? "\n" : "")
				+ "\n" + header + "\nbegin\nend;\n";
			document.insertString(document.getTextLength(), text);
			PsiDocumentManager.getInstance(project).commitDocument(document);
		}

		@Nullable
		private static KmrPascalEvent eventOf(@NotNull PsiFile file, @NotNull KmrPascalDirective directive)
		{
			return directive.eventPart == null ? null
				: KmrPascalEvents.getInstance(file.getProject()).findByEventName(KmrPascalInspectionUtil.versionOf(file), directive.eventPart.name);
		}
	}

}
