package dev.greeny.kmr.language.resolve;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import dev.greeny.kmr.language.psi.*;
import dev.greeny.kmr.language.stubs.KmrPascalStubLibrary;
import dev.greeny.kmr.language.stubs.KmrPascalStubScope;
import dev.greeny.kmr.language.unit.KmrPascalCompilationUnit;
import dev.greeny.kmr.language.unit.KmrPascalResolveContextService;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Name resolution, case-insensitive like Pascal. Scope order for a bare identifier:
 * <ol>
 *   <li>record fields brought in by enclosing {@code with} statements (innermost first)</li>
 *   <li>the enclosing routine: parameters, local declarations, {@code Result}, the routine's own name</li>
 *   <li>top-level declarations of the compilation unit the file is analysed in</li>
 *   <li>the API stubs of the unit's target game version (code inside the stubs only sees the stubs)</li>
 * </ol>
 * Qualified references ({@code a.b}) resolve through the declared record type of the qualifier.
 * Type names resolve to local, then unit-level type declarations.
 */
public final class KmrPascalResolver
{

	private KmrPascalResolver()
	{
	}

	@NotNull
	public static List<PsiElement> resolve(@NotNull KmrPascalReferenceElement reference)
	{
		String name = reference.getReferenceName();
		if (reference instanceof KmrPascalTypeReference) {
			return resolveType(reference, name);
		}
		KmrPascalExpression qualifier = reference.getQualifier();
		if (qualifier != null) {
			KmrPascalRecordType record = KmrPascalTypeUtil.recordTypeOf(qualifier);
			return record == null ? List.of() : new ArrayList<>(KmrPascalTypeUtil.findFields(record, name));
		}
		return resolveUnqualified(reference, name);
	}

	@NotNull
	private static List<PsiElement> resolveUnqualified(@NotNull PsiElement place, @NotNull String name)
	{
		// 1. with statements
		for (KmrPascalWithStatement with = PsiTreeUtil.getParentOfType(place, KmrPascalWithStatement.class);
			 with != null;
			 with = PsiTreeUtil.getParentOfType(with, KmrPascalWithStatement.class)) {
			if (isInsideWithBody(place, with)) {
				List<KmrPascalExpression> subjects = with.getExpressionList();
				// the last subject wins in Pascal, so search from the end
				for (int i = subjects.size() - 1; i >= 0; i--) {
					KmrPascalRecordType record = KmrPascalTypeUtil.recordTypeOf(subjects.get(i));
					if (record != null) {
						List<KmrPascalFieldIdentifier> fields = KmrPascalTypeUtil.findFields(record, name);
						if (!fields.isEmpty()) {
							return new ArrayList<>(fields);
						}
					}
				}
			}
		}

		// 2. enclosing routine
		KmrPascalRoutineDeclaration routine = PsiTreeUtil.getParentOfType(place, KmrPascalRoutineDeclaration.class);
		if (routine != null) {
			List<PsiElement> locals = new ArrayList<>();
			for (KmrPascalParameterDeclaration parameter : routine.getParameters()) {
				for (KmrPascalParameterIdentifier identifier : parameter.getParameterIdentifierList()) {
					if (name.equalsIgnoreCase(identifier.getName())) {
						locals.add(identifier);
					}
				}
			}
			for (PsiElement child : routine.getChildren()) {
				for (KmrPascalNamedElement declaration : KmrPascalCompilationUnit.topLevelDeclarations(child)) {
					if (name.equalsIgnoreCase(declaration.getName())) {
						locals.add(declaration);
					}
				}
			}
			if (routine instanceof KmrPascalFunctionDeclaration && name.equalsIgnoreCase("Result")) {
				locals.add(routine);
			}
			if (name.equalsIgnoreCase(routine.getName())) {
				locals.add(routine);
			}
			if (!locals.isEmpty()) {
				return locals;
			}
		}

		// 3. unit globals (stub files are their own world: skip straight to the stub scope)
		PsiFile file = place.getContainingFile();
		if (!KmrPascalStubLibrary.isStubFile(file)) {
			List<KmrPascalNamedElement> globals = unitOf(place).findGlobals(name);
			if (!globals.isEmpty()) {
				return new ArrayList<>(globals);
			}
		}

		// 4. API stubs
		return KmrPascalStubScope.getInstance(place.getProject()).findDeclarations(file, name);
	}

	@NotNull
	private static List<PsiElement> resolveType(@NotNull PsiElement place, @NotNull String name)
	{
		KmrPascalRoutineDeclaration routine = PsiTreeUtil.getParentOfType(place, KmrPascalRoutineDeclaration.class);
		if (routine != null) {
			List<PsiElement> locals = new ArrayList<>();
			for (KmrPascalTypeDeclarations declarations : PsiTreeUtil.getChildrenOfTypeAsList(routine, KmrPascalTypeDeclarations.class)) {
				for (KmrPascalTypeDeclaration declaration : declarations.getTypeDeclarationList()) {
					if (name.equalsIgnoreCase(declaration.getName())) {
						locals.add(declaration);
					}
				}
			}
			if (!locals.isEmpty()) {
				return locals;
			}
		}
		List<PsiElement> result = new ArrayList<>();
		PsiFile file = place.getContainingFile();
		if (!KmrPascalStubLibrary.isStubFile(file)) {
			for (KmrPascalNamedElement global : unitOf(place).findGlobals(name)) {
				if (global instanceof KmrPascalTypeDeclaration) {
					result.add(global);
				}
			}
		}
		if (result.isEmpty()) {
			for (PsiElement stub : KmrPascalStubScope.getInstance(place.getProject()).findDeclarations(file, name)) {
				if (stub instanceof KmrPascalTypeDeclaration) {
					result.add(stub);
				}
			}
		}
		return result;
	}

	/** True when the place is inside the statement controlled by the with, not inside its subject expressions. */
	private static boolean isInsideWithBody(@NotNull PsiElement place, @NotNull KmrPascalWithStatement with)
	{
		for (KmrPascalExpression subject : with.getExpressionList()) {
			if (PsiTreeUtil.isAncestor(subject, place, false)) {
				return false;
			}
		}
		return true;
	}

	@NotNull
	private static KmrPascalCompilationUnit unitOf(@NotNull PsiElement place)
	{
		return KmrPascalResolveContextService.getInstance(place.getProject()).getUnitFor(place.getContainingFile());
	}

}
