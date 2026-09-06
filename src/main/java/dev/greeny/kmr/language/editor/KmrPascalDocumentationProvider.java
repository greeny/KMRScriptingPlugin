package dev.greeny.kmr.language.editor;

import com.intellij.lang.documentation.AbstractDocumentationProvider;
import com.intellij.lang.documentation.DocumentationMarkup;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiDocCommentBase;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.SyntaxTraverser;
import com.intellij.psi.util.PsiTreeUtil;
import dev.greeny.kmr.language.psi.*;
import dev.greeny.kmr.language.resolve.KmrPascalCallable;
import dev.greeny.kmr.language.stubs.KmrPascalStubLibrary;
import dev.greeny.kmr.language.types.KmrTypePresenter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

/**
 * Quick documentation (Ctrl+Q) and navigation info for declarations, rendered from their doc comments; also the
 * in-editor rendering of block doc comments (Reader Mode / "Render documentation comments"), which the read-only API
 * stubs get by default.
 */
public class KmrPascalDocumentationProvider extends AbstractDocumentationProvider
{

	@Override
	public @Nullable String getQuickNavigateInfo(PsiElement element, PsiElement originalElement)
	{
		if (!(element instanceof KmrPascalNamedElement)) {
			return null;
		}
		return StringUtil.escapeXmlEntities(signature((KmrPascalNamedElement) element) + " [" + location(element) + "]");
	}

	@Override
	public @Nullable String generateDoc(PsiElement element, @Nullable PsiElement originalElement)
	{
		if (!(element instanceof KmrPascalNamedElement)) {
			return null;
		}
		KmrPascalNamedElement declaration = (KmrPascalNamedElement) element;
		KmrPascalDocComment doc = KmrPascalDocComment.of(declaration);
		StringBuilder html = new StringBuilder();
		html.append(DocumentationMarkup.DEFINITION_START)
			.append(StringUtil.escapeXmlEntities(signature(declaration)))
			.append(DocumentationMarkup.DEFINITION_END);

		if (doc != null && !doc.getDescription().isEmpty()) {
			html.append(DocumentationMarkup.CONTENT_START)
				.append(StringUtil.escapeXmlEntities(doc.getDescription()).replace("\n", "<br>"))
				.append(DocumentationMarkup.CONTENT_END);
		}

		html.append(DocumentationMarkup.SECTIONS_START);
		if (doc != null) {
			appendTagSections(html, doc);
		}
		section(html, "Defined in", StringUtil.escapeXmlEntities(location(declaration)));
		html.append(DocumentationMarkup.SECTIONS_END);
		return html.toString();
	}

	/** Deprecated / Params / Returns / Kinds / See also / Available since, from the doc tags. */
	private static void appendTagSections(@NotNull StringBuilder html, @NotNull KmrPascalDocComment doc)
	{
		String deprecated = doc.getTagValue(KmrPascalDocComment.TAG_DEPRECATED);
		if (deprecated != null) {
			section(html, "Deprecated", StringUtil.escapeXmlEntities(deprecated.isEmpty() ? "yes" : deprecated));
		}
		List<String> params = doc.getTagValues(KmrPascalDocComment.TAG_PARAM);
		if (!params.isEmpty()) {
			section(html, "Params", nameValueList(params));
		}
		String returns = doc.getTagValue(KmrPascalDocComment.TAG_RETURN);
		if (returns != null && !returns.isEmpty()) {
			section(html, "Returns", StringUtil.escapeXmlEntities(returns));
		}
		List<String> kinds = doc.getTagValues(KmrPascalDocComment.TAG_KIND);
		if (!kinds.isEmpty()) {
			StringBuilder value = new StringBuilder();
			for (String kind : kinds) {
				int space = kind.indexOf(' ');
				// "@kind aUnitID unitId" (a parameter) or "@kind unitId" (the result / the declaration itself)
				value.append(space < 0 ? "<code>" + StringUtil.escapeXmlEntities(kind) + "</code>"
					: "<code>" + StringUtil.escapeXmlEntities(kind.substring(0, space)) + "</code> &ndash; " + StringUtil.escapeXmlEntities(kind.substring(space + 1))).append("<br>");
			}
			section(html, "Kinds", value.toString());
		}
		for (String see : doc.getTagValues(KmrPascalDocComment.TAG_SEE)) {
			section(html, "See also", StringUtil.escapeXmlEntities(see));
		}
		String since = doc.getTagValue("since");
		if (since != null && !since.isEmpty()) {
			section(html, "Available since", "r" + StringUtil.escapeXmlEntities(since));
		}
	}

	@NotNull
	private static String nameValueList(@NotNull List<String> entries)
	{
		StringBuilder value = new StringBuilder();
		for (String entry : entries) {
			int space = entry.indexOf(' ');
			String name = space < 0 ? entry : entry.substring(0, space);
			String text = space < 0 ? "" : entry.substring(space + 1);
			value.append("<code>").append(StringUtil.escapeXmlEntities(name)).append("</code>");
			if (!text.isEmpty()) {
				value.append(" &ndash; ").append(StringUtil.escapeXmlEntities(text));
			}
			value.append("<br>");
		}
		return value.toString();
	}

	// ---------------------------------------------------------------------------------------------------------------
	// rendered doc comments (Reader Mode)
	// ---------------------------------------------------------------------------------------------------------------

	/** Block comments standing above a declaration are documentation comments the editor may render in place. */
	@Override
	public void collectDocComments(@NotNull PsiFile file, @NotNull Consumer<? super PsiDocCommentBase> sink)
	{
		for (PsiComment comment : SyntaxTraverser.psiTraverser(file).filter(PsiComment.class)) {
			if (comment instanceof KmrPascalCommentElement && ((KmrPascalCommentElement) comment).getOwner() != null) {
				sink.accept((KmrPascalCommentElement) comment);
			}
		}
	}

	@Override
	public @Nullable String generateRenderedDoc(@NotNull PsiDocCommentBase comment)
	{
		KmrPascalDocComment doc = KmrPascalDocComment.parse(KmrPascalDocComment.stripDelimiters(comment.getText()));
		StringBuilder html = new StringBuilder();
		if (!doc.getDescription().isEmpty()) {
			html.append(DocumentationMarkup.CONTENT_START)
				.append(StringUtil.escapeXmlEntities(doc.getDescription()).replace("\n", "<br>"))
				.append(DocumentationMarkup.CONTENT_END);
		}
		StringBuilder sections = new StringBuilder();
		appendTagSections(sections, doc);
		if (sections.length() > 0) {
			html.append(DocumentationMarkup.SECTIONS_START).append(sections).append(DocumentationMarkup.SECTIONS_END);
		}
		return html.length() == 0 ? null : html.toString();
	}

	@Override
	public @Nullable PsiElement getDocumentationElementForLookupItem(PsiManager psiManager, Object object, PsiElement element)
	{
		return object instanceof PsiElement ? (PsiElement) object : null;
	}

	// ---------------------------------------------------------------------------------------------------------------

	/** {@code procedure Actions.ShowMsg(aHand: Integer; aText: AnsiString)}, {@code var Counter: Integer}, ... */
	@NotNull
	static String signature(@NotNull KmrPascalNamedElement declaration)
	{
		String name = String.valueOf(declaration.getName());
		KmrPascalCallable callable = KmrPascalCallable.of(declaration);
		if (callable != null) {
			String owner = declaration instanceof KmrPascalFieldIdentifier ? ownerName((KmrPascalFieldIdentifier) declaration) : null;
			return (callable.isFunction() ? "function " : "procedure ") + (owner == null ? "" : owner + ".") + name + callable.getSignatureText();
		}
		if (declaration instanceof KmrPascalParameterIdentifier) {
			return "parameter " + name + ": " + KmrTypePresenter.typeText((KmrPascalTypedIdentifier) declaration, true);
		}
		if (declaration instanceof KmrPascalFieldIdentifier) {
			String owner = ownerName((KmrPascalFieldIdentifier) declaration);
			return "field " + (owner == null ? "" : owner + ".") + name + ": " + KmrTypePresenter.typeText((KmrPascalTypedIdentifier) declaration, true);
		}
		if (declaration instanceof KmrPascalTypedIdentifier) {
			return "var " + name + ": " + KmrTypePresenter.typeText((KmrPascalTypedIdentifier) declaration, true);
		}
		if (declaration instanceof KmrPascalConstantDeclaration) {
			KmrPascalExpression value = ((KmrPascalConstantDeclaration) declaration).getExpression();
			return "const " + name + (value == null ? "" : " = " + value.getText());
		}
		if (declaration instanceof KmrPascalTypeDeclaration) {
			return "type " + name + " = " + KmrPascalCompletionContributor.typeText(((KmrPascalTypeDeclaration) declaration).getTypeSpec());
		}
		if (declaration instanceof KmrPascalEnumValue) {
			KmrPascalTypeDeclaration enumType = PsiTreeUtil.getParentOfType(declaration, KmrPascalTypeDeclaration.class);
			return name + (enumType == null ? "" : ": " + enumType.getName());
		}
		return name;
	}

	/**
	 * For a field of an API record: the variable of that type declared in the same file ({@code Actions} for
	 * {@code TKMScriptActions}); otherwise the record type's name.
	 */
	@Nullable
	static String ownerName(@NotNull KmrPascalFieldIdentifier field)
	{
		KmrPascalRecordType record = PsiTreeUtil.getParentOfType(field, KmrPascalRecordType.class);
		return record == null ? null : ownerOfRecord(record);
	}

	/** See {@link #ownerName(KmrPascalFieldIdentifier)}. */
	@Nullable
	public static String ownerOfRecord(@NotNull KmrPascalRecordType record)
	{
		KmrPascalTypeDeclaration recordType = PsiTreeUtil.getParentOfType(record, KmrPascalTypeDeclaration.class);
		if (recordType == null || recordType.getName() == null) {
			return null;
		}
		for (KmrPascalVarIdentifier variable : PsiTreeUtil.findChildrenOfType(recordType.getContainingFile(), KmrPascalVarIdentifier.class)) {
			KmrPascalTypeSpec type = variable.getType();
			KmrPascalTypeElement element = type == null ? null : type.getTypeElement();
			if (element instanceof KmrPascalTypeReference && recordType.getName().equalsIgnoreCase(((KmrPascalTypeReference) element).getReferenceName())) {
				return variable.getName();
			}
		}
		return recordType.getName();
	}

	@NotNull
	private static String location(@NotNull PsiElement element)
	{
		PsiFile file = element.getContainingFile();
		if (KmrPascalStubLibrary.isSystemStubFile(file)) {
			return "PascalScript standard library";
		}
		String version = KmrPascalStubLibrary.stubVersionOf(file.getVirtualFile());
		return version != null ? "KaM Remake API (" + version + ")" : file.getName();
	}

	private static void section(@NotNull StringBuilder html, @NotNull String header, @NotNull String valueHtml)
	{
		html.append(DocumentationMarkup.SECTION_HEADER_START).append(header).append(':')
			.append(DocumentationMarkup.SECTION_SEPARATOR).append(valueHtml).append(DocumentationMarkup.SECTION_END);
	}

}
