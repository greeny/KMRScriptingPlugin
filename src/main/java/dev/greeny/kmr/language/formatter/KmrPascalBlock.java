package dev.greeny.kmr.language.formatter;

import com.intellij.formatting.*;
import com.intellij.lang.ASTNode;
import com.intellij.psi.TokenType;
import com.intellij.psi.formatter.common.AbstractBlock;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import dev.greeny.kmr.language.psi.KmrPascalTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

import static dev.greeny.kmr.language.psi.KmrPascalTypes.*;

/**
 * Formatting block. Indentation rules (Pascal style, 2 spaces by default):
 * statements inside begin/end, fields inside record/end, branches and their statements inside case/of/end,
 * declarations inside var/const/type sections, and the single statement controlled by if/while/for/with/else when it
 * is not a begin/end block. Everything else (routine headers, begin/end keywords themselves) stays at column 0 of
 * its parent.
 */
public class KmrPascalBlock extends AbstractBlock
{

	/** Containers whose children (except the delimiting keywords) are indented. */
	private static final TokenSet INDENTING_CONTAINERS = TokenSet.create(STATEMENT_LIST, RECORD_TYPE, CASE_STATEMENT, CASE_BRANCH,
		CONSTANT_DECLARATIONS, VAR_DECLARATIONS, TYPE_DECLARATIONS, REPEAT_STATEMENT, ENUM_TYPE);

	/** Statements owning one controlled statement each: the statement is indented unless it is a begin/end block. */
	private static final TokenSet CONTROL_STATEMENTS = TokenSet.create(IF_STATEMENT, WHILE_STATEMENT, FOR_STATEMENT, WITH_STATEMENT);

	private static final TokenSet STATEMENT_TYPES = TokenSet.create(STATEMENT_LIST, IF_STATEMENT, WHILE_STATEMENT, REPEAT_STATEMENT, WITH_STATEMENT,
		FOR_STATEMENT, CASE_STATEMENT, EXIT_STATEMENT, BREAK_STATEMENT, CONTINUE_STATEMENT, ASSIGNMENT, EXPRESSION_STATEMENT, KmrPascalTypes.EMPTY); // AbstractBlock.EMPTY shadows the static import

	private static final TokenSet KEYWORDS_AT_CONTAINER_LEVEL = TokenSet.create(BEGIN, END, RECORD, CASE, OF, CONST, VAR, TYPE, REPEAT, UNTIL, LBRACKET, RBRACKET);

	private final SpacingBuilder spacingBuilder;
	private final Indent indent;
	private final int indentSize;

	public KmrPascalBlock(@NotNull ASTNode node, @Nullable Alignment alignment, @NotNull Indent indent, @NotNull SpacingBuilder spacingBuilder, int indentSize)
	{
		super(node, null, alignment);
		this.indent = indent;
		this.spacingBuilder = spacingBuilder;
		this.indentSize = indentSize;
	}

	@Override
	protected List<Block> buildChildren()
	{
		List<Block> blocks = new ArrayList<>();
		for (ASTNode child = myNode.getFirstChildNode(); child != null; child = child.getTreeNext()) {
			if (child.getElementType() == TokenType.WHITE_SPACE || child.getTextLength() == 0) {
				continue;
			}
			blocks.add(new KmrPascalBlock(child, null, childIndent(myNode, child, indentSize), spacingBuilder, indentSize));
		}
		return blocks;
	}

	/** Indent of a child relative to its parent. */
	@NotNull
	static Indent childIndent(@NotNull ASTNode parent, @NotNull ASTNode child, int indentSize)
	{
		IElementType parentType = parent.getElementType();
		IElementType childType = child.getElementType();

		if (INDENTING_CONTAINERS.contains(parentType)) {
			if (KEYWORDS_AT_CONTAINER_LEVEL.contains(childType)) {
				return Indent.getNoneIndent();
			}
			if (parentType == CASE_STATEMENT && !STATEMENT_TYPES.contains(childType) && childType != CASE_BRANCH && childType != ELSE && !isComment(childType)) {
				return Indent.getNoneIndent(); // the selector expression
			}
			if (parentType == CASE_STATEMENT && STATEMENT_TYPES.contains(childType)) {
				return Indent.getSpaceIndent(2 * indentSize); // statements of the else branch: one level deeper than "else"
			}
			if (parentType == REPEAT_STATEMENT && !STATEMENT_TYPES.contains(childType) && !isComment(childType)) {
				return Indent.getNoneIndent(); // the until condition
			}
			if (parentType == CASE_BRANCH && !STATEMENT_TYPES.contains(childType)) {
				return Indent.getNoneIndent(); // labels and the colon
			}
			return Indent.getNormalIndent();
		}
		if (CONTROL_STATEMENTS.contains(parentType) && STATEMENT_TYPES.contains(childType) && childType != STATEMENT_LIST) {
			return Indent.getNormalIndent();
		}
		if (parentType == FUNCTION_ARGUMENT_LIST || parentType == PARAMETER_DECLARATION || isExpression(parentType)) {
			return Indent.getContinuationWithoutFirstIndent();
		}
		return Indent.getNoneIndent();
	}

	private static boolean isComment(@NotNull IElementType type)
	{
		return type == COMMENT_A || type == COMMENT_B || dev.greeny.kmr.language.psi.KmrPascalTokenSets.DIRECTIVES.contains(type);
	}

	private static boolean isExpression(@NotNull IElementType type)
	{
		return type == COMPARISON_EXPRESSION || type == ADDITIVE_EXPRESSION || type == MULTIPLICATIVE_EXPRESSION || type == CALL_EXPRESSION
			|| type == ARRAY_EXPRESSION || type == PAREN_EXPRESSION || type == ASSIGNMENT;
	}

	@Override
	public Indent getIndent()
	{
		return indent;
	}

	/** Indent for a new line typed inside this block (Enter handling). */
	@Override
	protected @Nullable Indent getChildIndent()
	{
		IElementType type = myNode.getElementType();
		if (INDENTING_CONTAINERS.contains(type) || CONTROL_STATEMENTS.contains(type)) {
			return Indent.getNormalIndent();
		}
		if (type == FUNCTION_ARGUMENT_LIST || isExpression(type)) {
			return Indent.getContinuationWithoutFirstIndent();
		}
		return Indent.getNoneIndent();
	}

	@Override
	public @Nullable Spacing getSpacing(@Nullable Block child1, @NotNull Block child2)
	{
		return spacingBuilder.getSpacing(this, child1, child2);
	}

	@Override
	public boolean isLeaf()
	{
		return myNode.getFirstChildNode() == null;
	}

}
