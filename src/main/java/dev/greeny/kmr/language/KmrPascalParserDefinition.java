package dev.greeny.kmr.language;

import com.intellij.lang.ASTNode;
import com.intellij.lang.ParserDefinition;
import com.intellij.lang.PsiParser;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.project.Project;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IFileElementType;
import com.intellij.psi.tree.TokenSet;
import dev.greeny.kmr.language.parser.KmrPascalParser;
import dev.greeny.kmr.language.psi.KmrPascalFile;
import dev.greeny.kmr.language.psi.KmrPascalTokenSets;
import dev.greeny.kmr.language.psi.KmrPascalTypes;
import org.jetbrains.annotations.NotNull;

public class KmrPascalParserDefinition implements ParserDefinition
{

	public static final IFileElementType FILE = new IFileElementType(KmrPascalLanguage.INSTANCE);

	@NotNull
	@Override
	public Lexer createLexer(Project project)
	{
		return new KmrPascalLexerAdapter();
	}

	@NotNull
	@Override
	public PsiParser createParser(Project project)
	{
		return new KmrPascalParser();
	}

	@NotNull
	@Override
	public IFileElementType getFileNodeType()
	{
		return FILE;
	}

	@NotNull
	@Override
	public TokenSet getCommentTokens()
	{
		return KmrPascalTokenSets.COMMENTS;
	}

	@NotNull
	@Override
	public TokenSet getStringLiteralElements()
	{
		return KmrPascalTokenSets.STRINGS;
	}

	@NotNull
	@Override
	public PsiElement createElement(ASTNode node)
	{
		return KmrPascalTypes.Factory.createElement(node);
	}

	@NotNull
	@Override
	public PsiFile createFile(@NotNull FileViewProvider viewProvider)
	{
		return new KmrPascalFile(viewProvider);
	}

}
