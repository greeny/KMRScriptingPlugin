package dev.greeny.kmr.language;

import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.options.colors.AttributesDescriptor;
import com.intellij.openapi.options.colors.ColorDescriptor;
import com.intellij.openapi.options.colors.ColorSettingsPage;
import com.intellij.openapi.util.NlsContexts;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.Map;

public class KmrPascalColorSettingsPage implements ColorSettingsPage
{

	private static final AttributesDescriptor[] DESCRIPTORS = new AttributesDescriptor[]{
		new AttributesDescriptor("String", KmrPascalSyntaxHighlighter.STRING),
		new AttributesDescriptor("Number", KmrPascalSyntaxHighlighter.NUMBER),
		new AttributesDescriptor("Keyword", KmrPascalSyntaxHighlighter.KEYWORD),
		new AttributesDescriptor("Comment", KmrPascalSyntaxHighlighter.COMMENT),
		new AttributesDescriptor("Directive", KmrPascalSyntaxHighlighter.DIRECTIVE),
		new AttributesDescriptor("Doc comment tag (@since, @param)", KmrPascalSyntaxHighlighter.DOC_TAG),
		new AttributesDescriptor("Doc comment tag value", KmrPascalSyntaxHighlighter.DOC_TAG_VALUE),
		new AttributesDescriptor("Operator", KmrPascalSyntaxHighlighter.OPERATOR),
		new AttributesDescriptor("Dot", KmrPascalSyntaxHighlighter.DOT),
		new AttributesDescriptor("Comma", KmrPascalSyntaxHighlighter.COMMA),
		new AttributesDescriptor("Semicolon", KmrPascalSyntaxHighlighter.SEMICOLON),
		new AttributesDescriptor("Bracket", KmrPascalSyntaxHighlighter.BRACKETS),
		new AttributesDescriptor("Constant value", KmrPascalSyntaxHighlighter.CONSTANTS),
		new AttributesDescriptor("Variable", KmrPascalSyntaxHighlighter.VARIABLES),
		new AttributesDescriptor("Bad character", KmrPascalSyntaxHighlighter.BAD_CHARACTER),
		new AttributesDescriptor("Inactive code (excluded by $IFDEF)", KmrPascalSyntaxHighlighter.INACTIVE_CODE),
	};

	@Override
	@Nullable
	public Icon getIcon()
	{
		return KmrPascalIcons.FILE;
	}

	@Override
	@NotNull
	public SyntaxHighlighter getHighlighter()
	{
		return new KmrPascalSyntaxHighlighter();
	}

	@NonNls
	@NotNull
	@Override
	public String getDemoText()
	{
		return """
		// this is a PascalScript file with support for KMR scripting

		{
			created by greeny
			greeny.dev on Discord
			<docTag>@since</docTag><docTagValue> 2026</docTagValue>
		}
	
		{$INCLUDE scriptMakingSense.script}
	
		const
			COLOR_RED = $FF0000;

		procedure OnMissionStart();
		var
			i: Integer;
			proc: Procedure;
		begin
			proc := @OnMissionStart;
			for i := 10 downto 1 do begin
				case i of
					1: Actions.ShowMsg(-1, 'Hello world!'#13#10'It''s working.');
					2..3: Actions.PlayerDefeat((i + 4) * 2);
					4: begin
						if (States.PlayerIsAI(4) = false) and (i <> COLOR_RED) then begin
							// make AI OP
						end;
					end;
				end;
			end;
		end;
		""";
	}

	@Override
	@Nullable
	public Map<String, TextAttributesKey> getAdditionalHighlightingTagToDescriptorMap()
	{
		return Map.of("docTag", KmrPascalSyntaxHighlighter.DOC_TAG, "docTagValue", KmrPascalSyntaxHighlighter.DOC_TAG_VALUE);
	}

	@Override
	public AttributesDescriptor @NotNull [] getAttributeDescriptors()
	{
		return DESCRIPTORS;
	}

	@Override
	public ColorDescriptor @NotNull [] getColorDescriptors()
	{
		return ColorDescriptor.EMPTY_ARRAY;
	}

	@NotNull
	@NlsContexts.ConfigurableName
	@Override
	public String getDisplayName()
	{
		return "PascalScript";
	}

}
