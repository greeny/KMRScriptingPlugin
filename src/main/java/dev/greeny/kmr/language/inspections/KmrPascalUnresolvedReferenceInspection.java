package dev.greeny.kmr.language.inspections;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiPolyVariantReference;
import com.intellij.psi.PsiReference;
import com.intellij.psi.util.PsiTreeUtil;
import dev.greeny.kmr.language.editor.KmrPascalDocumentationProvider;
import dev.greeny.kmr.language.editor.KmrPascalVersionStatusBarWidget;
import dev.greeny.kmr.language.psi.*;
import dev.greeny.kmr.language.resolve.KmrPascalTypeUtil;
import dev.greeny.kmr.language.stubs.KmrPascalStubLibrary;
import dev.greeny.kmr.language.stubs.KmrPascalStubScope;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Identifiers, members and type names that resolve to nothing. Members are only reported when the qualifier's
 * record type is known (so an unknown qualifier does not cascade); PascalScript built-ins are exempt. When the
 * name exists in another stub version, the message says so and offers to switch.
 */
public class KmrPascalUnresolvedReferenceInspection extends LocalInspectionTool
{

	@Override
	public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly)
	{
		return new PsiElementVisitor()
		{
			@Override
			public void visitElement(@NotNull PsiElement element)
			{
				if (element instanceof KmrPascalReferenceElement) {
					check((KmrPascalReferenceElement) element, holder);
				}
			}
		};
	}

	private static void check(@NotNull KmrPascalReferenceElement reference, @NotNull ProblemsHolder holder)
	{
		if (KmrPascalInspectionUtil.shouldSkip(reference)) {
			return;
		}
		PsiReference psiReference = reference.getReference();
		if (psiReference == null || psiReference instanceof PsiPolyVariantReference && ((PsiPolyVariantReference) psiReference).multiResolve(false).length > 0
			|| !(psiReference instanceof PsiPolyVariantReference) && psiReference.resolve() != null) {
			return;
		}
		String name = reference.getReferenceName();
		PsiFile file = reference.getContainingFile();

		if (reference instanceof KmrPascalTypeReference) {
			if (!KmrPascalBuiltins.isBuiltinType(name)) {
				holder.registerProblem(reference, "Unknown type '" + name + "'", ProblemHighlightType.LIKE_UNKNOWN_SYMBOL, fixes(file, name, true));
			}
			return;
		}

		KmrPascalExpression qualifier = reference.getQualifier();
		if (qualifier != null) {
			KmrPascalRecordType record = KmrPascalTypeUtil.recordTypeOf(qualifier);
			if (record == null) {
				return; // unknown qualifier: nothing reliable to say
			}
			String owner = KmrPascalDocumentationProvider.ownerOfRecord(record);
			String versionNote = otherVersionsWithMember(file, record, name);
			KmrPascalInspectionUtil.report(holder, reference, reference.getNameIdentifier().getTextRangeInParent(),
				"Unknown member '" + name + "'" + (owner == null ? "" : " of " + owner) + versionNote,
				ProblemHighlightType.LIKE_UNKNOWN_SYMBOL, fixes(file, name, false));
			return;
		}

		if (KmrPascalBuiltins.isBuiltinType(name)) {
			return; // a type cast such as Integer(x)
		}
		// standard routines resolve into System.script; a well-known Delphi routine that did not resolve is missing on purpose
		String replacement = KmrPascalBuiltins.replacementFor(name);
		String note = replacement != null
			? " (not available in KaM Remake's PascalScript; use " + replacement + ")"
			: otherVersionsWithGlobal(file, name);
		holder.registerProblem(reference, "Unresolved identifier '" + name + "'" + note,
			ProblemHighlightType.LIKE_UNKNOWN_SYMBOL, fixes(file, name, false));
	}

	// ---------------------------------------------------------------------------------------------------------------

	@NotNull
	private static String otherVersionsWithGlobal(@NotNull PsiFile file, @NotNull String name)
	{
		List<String> versions = versionsWithGlobal(file, name);
		return versions.isEmpty() ? "" : " (available in game version " + String.join(", ", versions) + ")";
	}

	@NotNull
	private static List<String> versionsWithGlobal(@NotNull PsiFile file, @NotNull String name)
	{
		String current = KmrPascalInspectionUtil.versionOf(file);
		KmrPascalStubScope stubs = KmrPascalStubScope.getInstance(file.getProject());
		List<String> result = new ArrayList<>();
		for (String version : KmrPascalStubLibrary.VERSIONS) {
			if (version.equals(current)) {
				continue;
			}
			for (KmrPascalStubScope.Declaration declaration : stubs.declarations(version).getOrDefault(name.toLowerCase(), List.of())) {
				if (!declaration.hidden) {
					result.add(version);
					break;
				}
			}
		}
		return result;
	}

	/** For a member of an API record: the other versions whose record of the same type name has that member. */
	@NotNull
	private static String otherVersionsWithMember(@NotNull PsiFile file, @NotNull KmrPascalRecordType record, @NotNull String member)
	{
		KmrPascalTypeDeclaration typeDeclaration = PsiTreeUtil.getParentOfType(record, KmrPascalTypeDeclaration.class);
		if (typeDeclaration == null || typeDeclaration.getName() == null || !KmrPascalStubLibrary.isStubFile(record.getContainingFile())) {
			return "";
		}
		String current = KmrPascalInspectionUtil.versionOf(file);
		List<String> result = new ArrayList<>();
		for (String version : KmrPascalStubLibrary.VERSIONS) {
			if (version.equals(current)) {
				continue;
			}
			for (PsiFile stub : KmrPascalStubLibrary.getInstance(file.getProject()).getStubSet(version).scopePsiFiles(file.getProject())) {
				for (KmrPascalTypeDeclaration candidate : PsiTreeUtil.findChildrenOfType(stub, KmrPascalTypeDeclaration.class)) {
					KmrPascalTypeElement type = candidate.getTypeSpec().getTypeElement();
					if (typeDeclaration.getName().equalsIgnoreCase(candidate.getName()) && type instanceof KmrPascalRecordType
						&& !KmrPascalTypeUtil.findFields((KmrPascalRecordType) type, member).isEmpty()) {
						result.add(version);
					}
				}
			}
		}
		return result.isEmpty() ? "" : " (available in game version " + String.join(", ", result) + ")";
	}

	@NotNull
	private static LocalQuickFix[] fixes(@NotNull PsiFile file, @NotNull String name, boolean type)
	{
		List<LocalQuickFix> fixes = new ArrayList<>();
		String current = KmrPascalInspectionUtil.versionOf(file);
		for (String version : KmrPascalStubLibrary.VERSIONS) {
			if (!version.equals(current) && existsIn(file, version, name)) {
				fixes.add(new SwitchGameVersionFix(version));
			}
		}
		return fixes.toArray(LocalQuickFix.EMPTY_ARRAY);
	}

	/** Whether the name is a visible top-level stub declaration or a record member in the given version. */
	private static boolean existsIn(@NotNull PsiFile file, @NotNull String version, @NotNull String name)
	{
		KmrPascalStubScope stubs = KmrPascalStubScope.getInstance(file.getProject());
		for (KmrPascalStubScope.Declaration declaration : stubs.declarations(version).getOrDefault(name.toLowerCase(), List.of())) {
			if (!declaration.hidden) {
				return true;
			}
		}
		for (PsiFile stub : KmrPascalStubLibrary.getInstance(file.getProject()).getStubSet(version).scopePsiFiles(file.getProject())) {
			for (KmrPascalRecordType record : PsiTreeUtil.findChildrenOfType(stub, KmrPascalRecordType.class)) {
				if (!KmrPascalTypeUtil.findFields(record, name).isEmpty()) {
					return true;
				}
			}
		}
		return false;
	}

	/** Quick fix: change the project-wide game version. */
	static final class SwitchGameVersionFix implements LocalQuickFix
	{
		private final String version;

		SwitchGameVersionFix(@NotNull String version)
		{
			this.version = version;
		}

		@Override
		public @NotNull String getFamilyName()
		{
			return "Switch game version to " + version;
		}

		@Override
		public boolean startInWriteAction()
		{
			return false;
		}

		@Override
		public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor)
		{
			KmrPascalVersionStatusBarWidget.setDefaultVersion(project, version);
		}
	}

}
