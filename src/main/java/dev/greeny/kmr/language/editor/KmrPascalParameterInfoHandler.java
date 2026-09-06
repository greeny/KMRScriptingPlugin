package dev.greeny.kmr.language.editor;

import com.intellij.lang.ASTNode;
import com.intellij.lang.parameterInfo.*;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import com.intellij.psi.util.PsiTreeUtil;
import dev.greeny.kmr.language.psi.*;
import dev.greeny.kmr.language.resolve.KmrPascalCallable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Ctrl+P inside a call: parameters of the called routine or procedural-type field, current one highlighted.
 * <p>
 * The parameter owner is the {@link KmrPascalCallExpression} when the call is complete. While the user is still
 * typing ({@code Foo(1, }) the parser produces no call node, so the owner is the loose "(" token and the callee and
 * argument index are derived from the tokens around it.
 */
public class KmrPascalParameterInfoHandler implements ParameterInfoHandler<PsiElement, KmrPascalCallable>
{

	private static final TokenSet STOP_TOKENS = TokenSet.create(KmrPascalTypes.SEMI, KmrPascalTypes.BEGIN, KmrPascalTypes.END, KmrPascalTypes.THEN,
		KmrPascalTypes.DO, KmrPascalTypes.ELSE, KmrPascalTypes.ASSIGN, KmrPascalTypes.UNTIL, KmrPascalTypes.OF);

	@Override
	public @Nullable PsiElement findElementForParameterInfo(@NotNull CreateParameterInfoContext context)
	{
		PsiElement owner = findOwner(context.getFile(), context.getOffset());
		if (owner == null) {
			return null;
		}
		KmrPascalCallable callable = calleeOf(owner);
		if (callable == null) {
			return null;
		}
		context.setItemsToShow(new Object[]{callable});
		return owner;
	}

	@Override
	public void showParameterInfo(@NotNull PsiElement owner, @NotNull CreateParameterInfoContext context)
	{
		PsiElement lparen = lparenOf(owner);
		context.showHint(owner, lparen == null ? owner.getTextOffset() : lparen.getTextOffset() + 1, this);
	}

	@Override
	public @Nullable PsiElement findElementForUpdatingParameterInfo(@NotNull UpdateParameterInfoContext context)
	{
		return findOwner(context.getFile(), context.getOffset());
	}

	@Override
	public void updateParameterInfo(@NotNull PsiElement owner, @NotNull UpdateParameterInfoContext context)
	{
		context.setCurrentParameter(argumentIndexAt(owner, context.getOffset()));
	}

	@Override
	public void updateUI(KmrPascalCallable callable, @NotNull ParameterInfoUIContext context)
	{
		List<KmrPascalParameterIdentifier> parameters = callable.getParameterIdentifiers();
		if (parameters.isEmpty()) {
			context.setupUIComponentPresentation("<no parameters>", -1, -1, false, false, false, context.getDefaultParameterColor());
			return;
		}
		StringBuilder text = new StringBuilder();
		int highlightStart = -1;
		int highlightEnd = -1;
		int current = context.getCurrentParameterIndex();
		for (int i = 0; i < parameters.size(); i++) {
			if (i > 0) {
				text.append(", ");
			}
			if (i == current) {
				highlightStart = text.length();
			}
			KmrPascalParameterIdentifier parameter = parameters.get(i);
			KmrPascalParameterDeclaration group = (KmrPascalParameterDeclaration) parameter.getParent();
			KmrPascalArgumentModifier modifier = group.getArgumentModifier();
			if (modifier != null) {
				text.append(modifier.getText()).append(' ');
			}
			text.append(parameter.getName()).append(": ").append(dev.greeny.kmr.language.types.KmrTypePresenter.typeText(parameter, true));
			if (i == current) {
				highlightEnd = text.length();
			}
		}
		context.setupUIComponentPresentation(text.toString(), highlightStart, highlightEnd, false,
			callable.getDeprecationMessage() != null, false, context.getDefaultParameterColor());
	}

	// ---------------------------------------------------------------------------------------------------------------

	/** The call expression whose parentheses contain the offset, or the loose "(" of an unfinished call. */
	@Nullable
	static PsiElement findOwner(@NotNull PsiFile file, int offset)
	{
		KmrPascalCallExpression call = enclosingCall(file.findElementAt(offset), offset);
		if (call == null) {
			call = enclosingCall(previousToken(file, offset), offset);
		}
		return call != null ? call : findUnfinishedCall(file, offset);
	}

	@Nullable
	private static KmrPascalCallExpression enclosingCall(@Nullable PsiElement element, int offset)
	{
		KmrPascalCallExpression call = PsiTreeUtil.getParentOfType(element, KmrPascalCallExpression.class, false);
		while (call != null && !isInsideParentheses(call, offset)) {
			call = PsiTreeUtil.getParentOfType(call, KmrPascalCallExpression.class, true);
		}
		return call;
	}

	private static boolean isInsideParentheses(@NotNull KmrPascalCallExpression call, int offset)
	{
		PsiElement lparen = child(call, KmrPascalTypes.LBRACKET);
		PsiElement rparen = child(call, KmrPascalTypes.RBRACKET);
		if (lparen == null || offset <= lparen.getTextOffset()) {
			return false;
		}
		return rparen == null || offset <= rparen.getTextOffset();
	}

	/**
	 * Walks back over tokens from the offset to the nearest unmatched "(" that is directly preceded by an
	 * expression; returns that "(" token, or null (stops at statement boundaries).
	 */
	@Nullable
	static PsiElement findUnfinishedCall(@NotNull PsiFile file, int offset)
	{
		int depth = 0;
		for (PsiElement leaf = previousToken(file, offset); leaf != null; leaf = previousToken(leaf)) {
			IElementType type = leaf.getNode().getElementType();
			if (type == KmrPascalTypes.RBRACKET || type == KmrPascalTypes.RSQUAREBRACKET) {
				depth++;
			} else if (type == KmrPascalTypes.LSQUAREBRACKET) {
				if (depth == 0) {
					return null;
				}
				depth--;
			} else if (type == KmrPascalTypes.LBRACKET) {
				if (depth == 0) {
					return calleeBefore(leaf) != null && !(leaf.getParent() instanceof KmrPascalCallExpression) ? leaf : null;
				}
				depth--;
			} else if (STOP_TOKENS.contains(type)) {
				return null;
			}
		}
		return null;
	}

	/** The expression that ends right before the given "(" token. */
	@Nullable
	private static KmrPascalExpression calleeBefore(@NotNull PsiElement lparen)
	{
		PsiElement before = previousToken(lparen);
		KmrPascalExpression callee = PsiTreeUtil.getParentOfType(before, KmrPascalExpression.class, false);
		while (callee != null && callee.getParent() instanceof KmrPascalExpression
			&& callee.getParent().getTextRange().getEndOffset() == callee.getTextRange().getEndOffset()) {
			callee = (KmrPascalExpression) callee.getParent();
		}
		return callee;
	}

	/** Zero-based index of the argument the offset is in (number of depth-0 commas before it). */
	static int argumentIndexAt(@NotNull PsiElement owner, int offset)
	{
		PsiElement lparen = lparenOf(owner);
		if (lparen == null) {
			return 0;
		}
		int index = 0;
		int depth = 0;
		for (PsiElement leaf = PsiTreeUtil.nextLeaf(lparen); leaf != null && leaf.getTextOffset() < offset; leaf = PsiTreeUtil.nextLeaf(leaf)) {
			IElementType type = leaf.getNode().getElementType();
			if (type == KmrPascalTypes.LBRACKET || type == KmrPascalTypes.LSQUAREBRACKET) {
				depth++;
			} else if (type == KmrPascalTypes.RBRACKET || type == KmrPascalTypes.RSQUAREBRACKET) {
				if (depth == 0) {
					break;
				}
				depth--;
			} else if (type == KmrPascalTypes.COMMA && depth == 0) {
				index++;
			} else if (STOP_TOKENS.contains(type)) {
				break;
			}
		}
		return index;
	}

	@Nullable
	static KmrPascalCallable calleeOf(@NotNull PsiElement owner)
	{
		KmrPascalExpression callee = owner instanceof KmrPascalCallExpression ? ((KmrPascalCallExpression) owner).getExpression() : calleeBefore(owner);
		PsiReference reference = callee == null ? null : callee.getReference();
		return reference == null ? null : KmrPascalCallable.of(reference.resolve());
	}

	@Nullable
	private static PsiElement lparenOf(@NotNull PsiElement owner)
	{
		return owner instanceof KmrPascalCallExpression ? child(owner, KmrPascalTypes.LBRACKET) : owner;
	}

	/** The last token that ends at or before the offset, skipping whitespace and comments. */
	@Nullable
	private static PsiElement previousToken(@NotNull PsiFile file, int offset)
	{
		PsiElement leaf = offset > 0 ? file.findElementAt(offset - 1) : null;
		return leaf == null || leaf instanceof PsiWhiteSpace || leaf instanceof PsiComment ? previousToken(leaf == null ? file.findElementAt(Math.max(0, offset - 1)) : leaf) : leaf;
	}

	@Nullable
	private static PsiElement previousToken(@Nullable PsiElement leaf)
	{
		PsiElement previous = leaf == null ? null : PsiTreeUtil.prevLeaf(leaf);
		while (previous instanceof PsiWhiteSpace || previous instanceof PsiComment) {
			previous = PsiTreeUtil.prevLeaf(previous);
		}
		return previous;
	}

	@Nullable
	private static PsiElement child(@NotNull PsiElement element, @NotNull IElementType type)
	{
		ASTNode node = element.getNode().findChildByType(type);
		return node == null ? null : node.getPsi();
	}

}
