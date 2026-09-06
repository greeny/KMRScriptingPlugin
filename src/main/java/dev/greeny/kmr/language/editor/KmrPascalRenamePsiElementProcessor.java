package dev.greeny.kmr.language.editor;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.refactoring.rename.RenamePsiElementProcessor;
import com.intellij.util.containers.MultiMap;
import dev.greeny.kmr.language.psi.*;
import dev.greeny.kmr.language.stubs.KmrPascalStubLibrary;
import dev.greeny.kmr.language.stubs.KmrPascalStubScope;
import dev.greeny.kmr.language.unit.KmrPascalCompilationUnit;
import dev.greeny.kmr.language.unit.KmrPascalResolveContextService;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Rename conflicts: a name that is already declared in the same scope (the routine, the record, the compilation
 * unit), or that the KaM Remake API / PascalScript's standard library already defines ({@code Actions}, {@code S},
 * {@code Length}). Shown in the usual "problems detected" dialog before the rename is carried out.
 */
public class KmrPascalRenamePsiElementProcessor extends RenamePsiElementProcessor
{

	@Override
	public boolean canProcessElement(@NotNull PsiElement element)
	{
		return element instanceof KmrPascalNamedElement && !KmrPascalStubLibrary.isStubFile(element.getContainingFile());
	}

	@Override
	public void findExistingNameConflicts(@NotNull PsiElement element, @NotNull String newName, @NotNull MultiMap<PsiElement, String> conflicts)
	{
		PsiFile file = element.getContainingFile();
		if (file == null) {
			return;
		}
		String quoted = "'" + newName + "'";
		for (PsiElement existing : sameScopeDeclarations(element, newName)) {
			if (existing != element && !existing.isEquivalentTo(element)) {
				conflicts.putValue(existing, quoted + " is already declared in " + scopeName(existing));
			}
		}
		for (PsiElement builtin : KmrPascalStubScope.getInstance(element.getProject()).findDeclarations(file.getOriginalFile(), newName)) {
			boolean standard = KmrPascalStubLibrary.isSystemStubFile(builtin.getContainingFile());
			conflicts.putValue(builtin, quoted + " is already defined by " + (standard ? "PascalScript's standard library" : "the KaM Remake API"));
		}
	}

	/** Declarations the renamed element would clash with: same routine, same record, or the unit's globals. */
	@NotNull
	private static List<PsiElement> sameScopeDeclarations(@NotNull PsiElement element, @NotNull String name)
	{
		List<PsiElement> result = new ArrayList<>();
		if (element instanceof KmrPascalFieldIdentifier) {
			KmrPascalRecordType record = PsiTreeUtil.getParentOfType(element, KmrPascalRecordType.class);
			if (record != null) {
				for (KmrPascalFieldIdentifier field : PsiTreeUtil.findChildrenOfType(record, KmrPascalFieldIdentifier.class)) {
					if (name.equalsIgnoreCase(field.getName())) {
						result.add(field);
					}
				}
			}
			return result;
		}
		KmrPascalRoutineDeclaration routine = PsiTreeUtil.getParentOfType(element, KmrPascalRoutineDeclaration.class);
		if (routine != null) {
			for (KmrPascalParameterDeclaration group : routine.getParameters()) {
				for (KmrPascalParameterIdentifier parameter : group.getParameterIdentifierList()) {
					if (name.equalsIgnoreCase(parameter.getName())) {
						result.add(parameter);
					}
				}
			}
			for (PsiElement child : routine.getChildren()) {
				for (KmrPascalNamedElement local : KmrPascalCompilationUnit.topLevelDeclarations(child)) {
					if (name.equalsIgnoreCase(local.getName())) {
						result.add(local);
					}
				}
			}
			if (name.equalsIgnoreCase(routine.getName())) {
				result.add(routine);
			}
			return result;
		}
		KmrPascalCompilationUnit unit = KmrPascalResolveContextService.getInstance(element.getProject()).getUnitFor(element.getContainingFile().getOriginalFile());
		result.addAll(unit.findGlobals(name));
		return result;
	}

	@NotNull
	private static String scopeName(@NotNull PsiElement declaration)
	{
		KmrPascalRoutineDeclaration routine = PsiTreeUtil.getParentOfType(declaration, KmrPascalRoutineDeclaration.class);
		if (declaration instanceof KmrPascalFieldIdentifier) {
			KmrPascalTypeDeclaration type = PsiTreeUtil.getParentOfType(declaration, KmrPascalTypeDeclaration.class);
			return type == null ? "this record" : "record " + type.getName();
		}
		if (routine != null && routine != declaration) {
			return (routine instanceof KmrPascalFunctionDeclaration ? "function " : "procedure ") + routine.getName();
		}
		PsiFile file = declaration.getContainingFile();
		return file == null ? "this unit" : file.getName();
	}

}
