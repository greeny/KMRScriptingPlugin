package dev.greeny.kmr.language.unit;

import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.util.PsiTreeUtil;
import dev.greeny.kmr.language.psi.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * An entry point plus everything it (transitively) includes, in include order, with the preprocessor applied:
 * for every inclusion the ranges of text that survive {@code $IFDEF}/{@code $IFNDEF}, the final set of defines,
 * and the problems found on the way. Built by {@link KmrPascalPreprocessor}, cached per entry point file.
 */
public final class KmrPascalCompilationUnit
{

	private static final Key<com.intellij.psi.util.CachedValue<KmrPascalCompilationUnit>> KEY = Key.create("KmrPascalCompilationUnit");

	/** One occurrence of a file in the unit; a file included twice has two inclusions. */
	public static final class Inclusion
	{
		public final PsiFile file;
		public final List<TextRange> activeRanges;
		@Nullable
		public final Inclusion includedFrom;

		Inclusion(@NotNull PsiFile file, @NotNull List<TextRange> activeRanges, @Nullable Inclusion includedFrom)
		{
			this.file = file;
			this.activeRanges = activeRanges;
			this.includedFrom = includedFrom;
		}

		public boolean isActive(int offset)
		{
			for (TextRange range : activeRanges) {
				if (range.containsOffset(offset)) {
					return true;
				}
			}
			return false;
		}

		/** Complement of the active ranges within the file. */
		@NotNull
		public List<TextRange> inactiveRanges()
		{
			List<TextRange> result = new ArrayList<>();
			int position = 0;
			for (TextRange range : activeRanges) {
				if (range.getStartOffset() > position) {
					result.add(TextRange.create(position, range.getStartOffset()));
				}
				position = Math.max(position, range.getEndOffset());
			}
			int length = file.getTextLength();
			if (position < length) {
				result.add(TextRange.create(position, length));
			}
			return result;
		}
	}

	public static final class Problem
	{
		public enum Kind
		{
			MISSING_INCLUDE, RECURSIVE_INCLUDE, DUPLICATE_ACTIVE_INCLUDE, UNBALANCED_CONDITIONAL, ELSE_WITHOUT_IF, ENDIF_WITHOUT_IF
		}

		public final Kind kind;
		public final PsiFile file;
		public final TextRange range;
		public final String message;

		Problem(@NotNull Kind kind, @NotNull PsiFile file, @NotNull TextRange range, @NotNull String message)
		{
			this.kind = kind;
			this.file = file;
			this.range = range;
			this.message = message;
		}
	}

	private final PsiFile entryPoint;
	private final List<Inclusion> inclusions;
	private final Set<String> defines;
	private final List<Problem> problems;
	private volatile Map<String, List<KmrPascalNamedElement>> globals;

	KmrPascalCompilationUnit(@NotNull PsiFile entryPoint, @NotNull List<Inclusion> inclusions, @NotNull Set<String> defines, @NotNull List<Problem> problems)
	{
		this.entryPoint = entryPoint;
		this.inclusions = Collections.unmodifiableList(inclusions);
		this.defines = Collections.unmodifiableSet(defines);
		this.problems = Collections.unmodifiableList(problems);
	}

	/** The unit rooted at the given file (which is treated as an entry point whether or not it is one). */
	@NotNull
	public static KmrPascalCompilationUnit of(@NotNull PsiFile entryPoint)
	{
		return CachedValuesManager.getManager(entryPoint.getProject()).getCachedValue(entryPoint, KEY, () ->
				CachedValueProvider.Result.create(
					KmrPascalPreprocessor.build(entryPoint),
					PsiModificationTracker.MODIFICATION_COUNT,
					VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS),
			false);
	}

	@NotNull
	public PsiFile getEntryPoint()
	{
		return entryPoint;
	}

	@NotNull
	public List<Inclusion> getInclusions()
	{
		return inclusions;
	}

	/** Symbols defined at the end of the unit (lower-cased). */
	@NotNull
	public Set<String> getDefines()
	{
		return defines;
	}

	@NotNull
	public List<Problem> getProblems()
	{
		return problems;
	}

	public boolean contains(@NotNull PsiFile file)
	{
		return firstInclusion(file) != null;
	}

	@Nullable
	public Inclusion firstInclusion(@NotNull PsiFile file)
	{
		for (Inclusion inclusion : inclusions) {
			if (inclusion.file.isEquivalentTo(file)) {
				return inclusion;
			}
		}
		return null;
	}

	/** True when the element's text survives the preprocessor in at least one inclusion of its file. */
	public boolean isActive(@NotNull PsiElement element)
	{
		PsiFile file = element.getContainingFile();
		int offset = element.getTextRange().getStartOffset();
		for (Inclusion inclusion : inclusions) {
			if (inclusion.file.isEquivalentTo(file) && inclusion.isActive(offset)) {
				return true;
			}
		}
		return false;
	}

	/** Ranges of a file that the preprocessor removes in its first inclusion; empty if the file is not in the unit. */
	@NotNull
	public List<TextRange> inactiveRanges(@NotNull PsiFile file)
	{
		Inclusion inclusion = firstInclusion(file);
		return inclusion == null ? Collections.emptyList() : inclusion.inactiveRanges();
	}

	/** Top-level declarations visible in the unit, keyed by lower-cased name, in include order. */
	@NotNull
	public Map<String, List<KmrPascalNamedElement>> getGlobals()
	{
		Map<String, List<KmrPascalNamedElement>> result = globals;
		if (result == null) {
			result = collectGlobals();
			globals = result;
		}
		return result;
	}

	@NotNull
	public List<KmrPascalNamedElement> findGlobals(@NotNull String name)
	{
		return getGlobals().getOrDefault(name.toLowerCase(), Collections.emptyList());
	}

	@NotNull
	private Map<String, List<KmrPascalNamedElement>> collectGlobals()
	{
		Map<String, List<KmrPascalNamedElement>> map = new HashMap<>();
		Set<PsiElement> seen = new HashSet<>();
		for (Inclusion inclusion : inclusions) {
			for (PsiElement child : inclusion.file.getChildren()) {
				for (KmrPascalNamedElement declaration : topLevelDeclarations(child)) {
					if (inclusion.isActive(declaration.getTextRange().getStartOffset()) && seen.add(declaration)) {
						String name = declaration.getName();
						if (name != null) {
							map.computeIfAbsent(name.toLowerCase(), k -> new ArrayList<>()).add(declaration);
						}
					}
				}
			}
		}
		return map;
	}

	/** The named elements introduced into the global scope by one top-level PSI child of a file. */
	@NotNull
	public static List<KmrPascalNamedElement> topLevelDeclarations(@NotNull PsiElement child)
	{
		List<KmrPascalNamedElement> result = new ArrayList<>();
		if (child instanceof KmrPascalConstantDeclarations) {
			result.addAll(((KmrPascalConstantDeclarations) child).getConstantDeclarationList());
		} else if (child instanceof KmrPascalVarDeclarations) {
			for (KmrPascalVarDeclaration declaration : ((KmrPascalVarDeclarations) child).getVarDeclarationList()) {
				result.addAll(declaration.getVarIdentifierList());
			}
		} else if (child instanceof KmrPascalTypeDeclarations) {
			for (KmrPascalTypeDeclaration declaration : ((KmrPascalTypeDeclarations) child).getTypeDeclarationList()) {
				result.add(declaration);
				// enum values are plain identifiers in the enclosing scope
				result.addAll(PsiTreeUtil.findChildrenOfType(declaration, KmrPascalEnumValue.class));
			}
		} else if (child instanceof KmrPascalRoutineDeclaration) {
			result.add((KmrPascalRoutineDeclaration) child);
		}
		return result;
	}

}
