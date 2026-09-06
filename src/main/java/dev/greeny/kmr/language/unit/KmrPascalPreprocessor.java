package dev.greeny.kmr.language.unit;

import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import dev.greeny.kmr.language.psi.KmrPascalFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

import static dev.greeny.kmr.language.unit.KmrPascalCompilationUnit.Inclusion;
import static dev.greeny.kmr.language.unit.KmrPascalCompilationUnit.Problem;

/**
 * Walks an entry point and its includes in include order, evaluating {@code $DEFINE / $UNDEF / $IFDEF / $IFNDEF /
 * $ELSE / $ENDIF} exactly like the game's preprocessor: only text inside active conditionals counts, includes are
 * followed only when active, and defines accumulate across files.
 */
public final class KmrPascalPreprocessor
{

	/** Guard against pathological include chains. */
	private static final int MAX_DEPTH = 64;

	private final PsiManager psiManager;
	private final PsiFile entryPoint;
	private final Set<String> defines = new HashSet<>();
	private final List<Inclusion> inclusions = new ArrayList<>();
	private final List<Problem> problems = new ArrayList<>();
	/** Files whose active content has already been included once, to report duplicate active inclusion. */
	private final Set<PsiFile> filesWithActiveContent = new HashSet<>();

	private KmrPascalPreprocessor(@NotNull PsiFile entryPoint)
	{
		this.entryPoint = entryPoint;
		this.psiManager = PsiManager.getInstance(entryPoint.getProject());
	}

	@NotNull
	static KmrPascalCompilationUnit build(@NotNull PsiFile entryPoint)
	{
		KmrPascalPreprocessor preprocessor = new KmrPascalPreprocessor(entryPoint);
		preprocessor.process(entryPoint, null, null, new ArrayDeque<>());
		return new KmrPascalCompilationUnit(entryPoint, preprocessor.inclusions, preprocessor.defines, preprocessor.problems);
	}

	private static final class Conditional
	{
		final boolean parentActive;
		final boolean conditionTrue;
		boolean inElse;
		final KmrPascalDirective opening;

		Conditional(boolean parentActive, boolean conditionTrue, KmrPascalDirective opening)
		{
			this.parentActive = parentActive;
			this.conditionTrue = conditionTrue;
			this.opening = opening;
		}

		boolean isActive()
		{
			return parentActive && (conditionTrue != inElse);
		}
	}

	private void process(@NotNull PsiFile file, @Nullable Inclusion includedFrom, @Nullable KmrPascalDirective includedBy, @NotNull Deque<PsiFile> stack)
	{
		stack.push(file);
		Deque<Conditional> conditionals = new ArrayDeque<>();
		List<TextRange> activeRanges = new ArrayList<>();
		Inclusion inclusion = new Inclusion(file, activeRanges, includedFrom);
		inclusions.add(inclusion);

		boolean active = true;
		int activeStart = 0;

		List<KmrPascalDirective> directives = KmrPascalDirective.collect(file);
		for (KmrPascalDirective directive : directives) {
			switch (directive.kind) {
				case IFDEF:
				case IFNDEF: {
					boolean defined = defines.contains(directive.argument.toLowerCase());
					conditionals.push(new Conditional(active, directive.kind == KmrPascalDirective.Kind.IFDEF ? defined : !defined, directive));
					break;
				}
				case ELSE: {
					Conditional top = conditionals.peek();
					if (top == null) {
						problems.add(new Problem(Problem.Kind.ELSE_WITHOUT_IF, file, directive.range, "{$ELSE} without {$IFDEF}/{$IFNDEF}"));
					} else {
						top.inElse = true;
					}
					break;
				}
				case ENDIF: {
					if (conditionals.poll() == null) {
						problems.add(new Problem(Problem.Kind.ENDIF_WITHOUT_IF, file, directive.range, "{$ENDIF} without {$IFDEF}/{$IFNDEF}"));
					}
					break;
				}
				case DEFINE:
					if (active && !directive.argument.isEmpty()) {
						defines.add(directive.argument.toLowerCase());
					}
					break;
				case UNDEF:
					if (active) {
						defines.remove(directive.argument.toLowerCase());
					}
					break;
				case INCLUDE:
					if (active) {
						include(file, directive, inclusion, stack);
					}
					break;
				default:
					break;
			}

			// The directive tokens themselves always count as active (they are never dimmed); only the text strictly
			// between a deactivating and a reactivating directive is inactive.
			boolean nowActive = conditionals.isEmpty() || conditionals.peek().isActive();
			if (active && !nowActive) {
				activeRanges.add(TextRange.create(activeStart, directive.range.getEndOffset()));
			} else if (!active && nowActive) {
				activeStart = directive.range.getStartOffset();
			}
			active = nowActive;
		}

		int length = file.getTextLength();
		if (active && length > activeStart) {
			activeRanges.add(TextRange.create(activeStart, length));
		}
		for (Conditional open : conditionals) {
			problems.add(new Problem(Problem.Kind.UNBALANCED_CONDITIONAL, file, open.opening.range, "Conditional directive is not closed by {$ENDIF} in this file"));
		}
		if (hasNonBlankActiveText(file, activeRanges, directives) && !filesWithActiveContent.add(file) && includedFrom != null && includedBy != null) {
			problems.add(new Problem(Problem.Kind.DUPLICATE_ACTIVE_INCLUDE, includedFrom.file, includedBy.range,
				"File '" + file.getName() + "' is included more than once with active content"));
		}
		stack.pop();
	}

	private void include(@NotNull PsiFile from, @NotNull KmrPascalDirective directive, @NotNull Inclusion fromInclusion, @NotNull Deque<PsiFile> stack)
	{
		VirtualFile fromFile = from.getVirtualFile();
		VirtualFile entryFile = entryPoint.getVirtualFile();
		if (fromFile == null || directive.argument.isEmpty()) {
			return;
		}
		VirtualFile target = KmrPascalIncludeResolver.resolve(fromFile, entryFile, directive.argument);
		PsiFile targetPsi = target == null ? null : psiManager.findFile(target);
		if (!(targetPsi instanceof KmrPascalFile)) {
			problems.add(new Problem(Problem.Kind.MISSING_INCLUDE, from, directive.range, "Cannot find included file '" + directive.argument + "'"));
			return;
		}
		if (stack.contains(targetPsi) || stack.size() > MAX_DEPTH) {
			problems.add(new Problem(Problem.Kind.RECURSIVE_INCLUDE, from, directive.range, "Recursive include of '" + directive.argument + "'"));
			return;
		}
		process(targetPsi, fromInclusion, directive, stack);
	}

	/** True when the active ranges contain anything besides whitespace and directive tokens. */
	private static boolean hasNonBlankActiveText(@NotNull PsiFile file, @NotNull List<TextRange> ranges, @NotNull List<KmrPascalDirective> directives)
	{
		CharSequence text = file.getViewProvider().getContents();
		int directiveIndex = 0;
		for (TextRange range : ranges) {
			for (int i = range.getStartOffset(); i < range.getEndOffset() && i < text.length(); i++) {
				while (directiveIndex < directives.size() && directives.get(directiveIndex).range.getEndOffset() <= i) {
					directiveIndex++;
				}
				if (directiveIndex < directives.size() && directives.get(directiveIndex).range.containsOffset(i)) {
					i = directives.get(directiveIndex).range.getEndOffset() - 1;
					continue;
				}
				if (!Character.isWhitespace(text.charAt(i))) {
					return true;
				}
			}
		}
		return false;
	}

}
