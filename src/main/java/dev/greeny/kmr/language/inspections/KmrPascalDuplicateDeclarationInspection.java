package dev.greeny.kmr.language.inspections;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import dev.greeny.kmr.language.psi.*;
import dev.greeny.kmr.language.stubs.KmrPascalStubLibrary;
import dev.greeny.kmr.language.stubs.KmrPascalStubScope;
import dev.greeny.kmr.language.unit.KmrPascalCompilationUnit;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * Names declared twice in one scope: unit-wide globals (across all included files, e.g. two OnTick handlers),
 * parameters and locals of a routine, record fields. Also warns when a global redeclares an API identifier.
 */
public class KmrPascalDuplicateDeclarationInspection extends LocalInspectionTool
{

	@Override
	public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly)
	{
		return new PsiElementVisitor()
		{
			@Override
			public void visitElement(@NotNull PsiElement element)
			{
				if (element instanceof PsiFile) {
					checkGlobals((PsiFile) element, holder);
				} else if (element instanceof KmrPascalRoutineDeclaration) {
					checkRoutine((KmrPascalRoutineDeclaration) element, holder);
				} else if (element instanceof KmrPascalRecordType) {
					checkRecord((KmrPascalRecordType) element, holder);
				}
			}
		};
	}

	private static void checkGlobals(@NotNull PsiFile file, @NotNull ProblemsHolder holder)
	{
		if (KmrPascalInspectionUtil.shouldSkip(file)) {
			return;
		}
		KmrPascalCompilationUnit unit = KmrPascalInspectionUtil.unitOf(file);
		KmrPascalStubScope stubs = KmrPascalStubScope.getInstance(file.getProject());
		for (Map.Entry<String, List<KmrPascalNamedElement>> entry : unit.getGlobals().entrySet()) {
			List<KmrPascalNamedElement> declarations = entry.getValue();
			for (KmrPascalNamedElement declaration : declarations) {
				if (!declaration.getContainingFile().isEquivalentTo(file.getOriginalFile()) || declaration.getNameIdentifier() == null) {
					continue;
				}
				PsiElement target = declarationInThisFile(file, declaration);
				if (target == null) {
					continue;
				}
				if (declarations.size() > 1) {
					holder.registerProblem(target, duplicateMessage(declaration, declarations, unit), ProblemHighlightType.GENERIC_ERROR);
				} else {
					List<PsiElement> builtins = stubs.findDeclarations(file.getOriginalFile(), entry.getKey());
					if (!builtins.isEmpty()) {
						boolean standard = KmrPascalStubLibrary.isSystemStubFile(builtins.get(0).getContainingFile());
						holder.registerProblem(target, "'" + declaration.getName() + "' redeclares a " + (standard ? "standard PascalScript identifier" : "built-in identifier of the KaM Remake API"),
							ProblemHighlightType.WARNING);
					}
				}
			}
		}
	}

	/** The unit is built on the original file's PSI; map the declaration back to the (possibly copied) inspected file. */
	private static PsiElement declarationInThisFile(@NotNull PsiFile file, @NotNull KmrPascalNamedElement declaration)
	{
		PsiElement identifier = declaration.getNameIdentifier();
		if (identifier == null) {
			return null;
		}
		if (file == declaration.getContainingFile()) {
			return identifier;
		}
		PsiElement leaf = file.findElementAt(identifier.getTextOffset());
		return leaf != null && leaf.getText().equals(identifier.getText()) ? leaf : null;
	}

	@NotNull
	private static String duplicateMessage(@NotNull KmrPascalNamedElement declaration, @NotNull List<KmrPascalNamedElement> all, @NotNull KmrPascalCompilationUnit unit)
	{
		Set<String> otherFiles = new TreeSet<>();
		for (KmrPascalNamedElement other : all) {
			if (other != declaration && !other.getContainingFile().isEquivalentTo(declaration.getContainingFile())) {
				otherFiles.add(other.getContainingFile().getName());
			}
		}
		String where = otherFiles.isEmpty() ? "" : " (also in " + String.join(", ", otherFiles) + ")";
		if (declaration instanceof KmrPascalRoutineDeclaration && KmrPascalInspectionUtil.isEventHandler((KmrPascalRoutineDeclaration) declaration)) {
			return "Event handler '" + declaration.getName() + "' is declared more than once in the compilation unit of " + unit.getEntryPoint().getName() + where;
		}
		return "'" + declaration.getName() + "' is already declared" + where;
	}

	private static void checkRoutine(@NotNull KmrPascalRoutineDeclaration routine, @NotNull ProblemsHolder holder)
	{
		if (KmrPascalInspectionUtil.shouldSkip(routine)) {
			return;
		}
		List<KmrPascalNamedElement> locals = new ArrayList<>();
		for (KmrPascalParameterDeclaration parameter : routine.getParameters()) {
			locals.addAll(parameter.getParameterIdentifierList());
		}
		for (PsiElement child : routine.getChildren()) {
			locals.addAll(KmrPascalCompilationUnit.topLevelDeclarations(child));
		}
		reportDuplicates(locals, holder);
	}

	private static void checkRecord(@NotNull KmrPascalRecordType record, @NotNull ProblemsHolder holder)
	{
		if (KmrPascalInspectionUtil.shouldSkip(record)) {
			return;
		}
		reportDuplicates(new ArrayList<>(PsiTreeUtil.findChildrenOfType(record, KmrPascalFieldIdentifier.class)), holder);
	}

	private static void reportDuplicates(@NotNull List<? extends KmrPascalNamedElement> declarations, @NotNull ProblemsHolder holder)
	{
		Map<String, KmrPascalNamedElement> seen = new HashMap<>();
		for (KmrPascalNamedElement declaration : declarations) {
			String name = declaration.getName();
			PsiElement identifier = declaration.getNameIdentifier();
			if (name == null || identifier == null) {
				continue;
			}
			KmrPascalNamedElement first = seen.putIfAbsent(name.toLowerCase(Locale.ROOT), declaration);
			if (first != null) {
				holder.registerProblem(identifier, "'" + name + "' is already declared in this scope", ProblemHighlightType.GENERIC_ERROR);
			}
		}
	}

}
