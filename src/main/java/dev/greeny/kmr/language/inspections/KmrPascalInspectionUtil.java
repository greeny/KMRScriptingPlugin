package dev.greeny.kmr.language.inspections;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiPolyVariantReference;
import com.intellij.psi.PsiReference;
import com.intellij.psi.ResolveResult;
import dev.greeny.kmr.language.psi.KmrPascalRoutineDeclaration;
import dev.greeny.kmr.language.stubs.KmrPascalEvents;
import dev.greeny.kmr.language.stubs.KmrPascalStubLibrary;
import dev.greeny.kmr.language.stubs.KmrPascalStubScope;
import dev.greeny.kmr.language.unit.KmrPascalCompilationUnit;
import dev.greeny.kmr.language.unit.KmrPascalDirective;
import dev.greeny.kmr.language.unit.KmrPascalResolveContextService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Shared helpers for the inspections. */
final class KmrPascalInspectionUtil
{

	private KmrPascalInspectionUtil()
	{
	}

	/** registerProblem with both a sub-range and a highlight type (no such overload exists on ProblemsHolder). */
	static void report(@NotNull com.intellij.codeInspection.ProblemsHolder holder, @NotNull PsiElement element, @NotNull com.intellij.openapi.util.TextRange rangeInElement,
					   @NotNull String message, @NotNull com.intellij.codeInspection.ProblemHighlightType type, com.intellij.codeInspection.LocalQuickFix... fixes)
	{
		holder.registerProblem(holder.getManager().createProblemDescriptor(element, rangeInElement, message, type, holder.isOnTheFly(), fixes));
	}

	/** Stub files are never inspected, nor is code the preprocessor removes in the current unit. */
	static boolean shouldSkip(@NotNull PsiElement element)
	{
		PsiFile file = element.getContainingFile();
		if (file == null || KmrPascalStubLibrary.isStubFile(file)) {
			return true;
		}
		return !unitOf(file).isActive(element);
	}

	@NotNull
	static KmrPascalCompilationUnit unitOf(@NotNull PsiFile file)
	{
		return KmrPascalResolveContextService.getInstance(file.getProject()).getUnitFor(file.getOriginalFile());
	}

	@NotNull
	static String versionOf(@NotNull PsiFile file)
	{
		return KmrPascalStubScope.getInstance(file.getProject()).versionFor(file.getOriginalFile());
	}

	/** Single resolve target, or null when unresolved or ambiguous. */
	@Nullable
	static PsiElement resolveSingle(@NotNull PsiElement element)
	{
		PsiReference reference = element.getReference();
		if (reference instanceof PsiPolyVariantReference) {
			ResolveResult[] results = ((PsiPolyVariantReference) reference).multiResolve(false);
			return results.length == 1 ? results[0].getElement() : null;
		}
		return reference == null ? null : reference.resolve();
	}

	/** True when the routine is a game event handler: named like an event, or registered with {$EVENT}. */
	static boolean isEventHandler(@NotNull KmrPascalRoutineDeclaration routine)
	{
		String name = routine.getName();
		if (name == null) {
			return false;
		}
		PsiFile file = routine.getContainingFile();
		if (KmrPascalEvents.getInstance(file.getProject()).findByHandlerName(versionOf(file), name) != null) {
			return true;
		}
		return registeredHandlers(unitOf(file)).contains(name.toLowerCase(Locale.ROOT));
	}

	/** Lower-cased procedure names registered through {$EVENT evtX:Name} anywhere in the unit. */
	@NotNull
	static Set<String> registeredHandlers(@NotNull KmrPascalCompilationUnit unit)
	{
		Set<String> result = new HashSet<>();
		for (KmrPascalCompilationUnit.Inclusion inclusion : unit.getInclusions()) {
			for (KmrPascalDirective directive : KmrPascalDirective.collect(inclusion.file)) {
				if (directive.kind == KmrPascalDirective.Kind.EVENT) {
					int colon = directive.argument.indexOf(':');
					if (colon >= 0) {
						result.add(directive.argument.substring(colon + 1).trim().toLowerCase(Locale.ROOT));
					}
				}
			}
		}
		return result;
	}

	/** The event name half of {$EVENT evtX:Name} for a registered handler, or null. */
	@Nullable
	static String registeredEventName(@NotNull KmrPascalCompilationUnit unit, @NotNull String handlerName)
	{
		for (KmrPascalCompilationUnit.Inclusion inclusion : unit.getInclusions()) {
			for (KmrPascalDirective directive : KmrPascalDirective.collect(inclusion.file)) {
				if (directive.kind == KmrPascalDirective.Kind.EVENT) {
					int colon = directive.argument.indexOf(':');
					if (colon >= 0 && directive.argument.substring(colon + 1).trim().equalsIgnoreCase(handlerName)) {
						return directive.argument.substring(0, colon).trim();
					}
				}
			}
		}
		return null;
	}

}
