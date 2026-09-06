package dev.greeny.kmr.language.formatter;

import com.intellij.application.options.CodeStyleAbstractConfigurable;
import com.intellij.application.options.CodeStyleAbstractPanel;
import com.intellij.application.options.IndentOptionsEditor;
import com.intellij.application.options.TabbedLanguageCodeStylePanel;
import com.intellij.lang.Language;
import com.intellij.psi.codeStyle.CodeStyleConfigurable;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider;
import dev.greeny.kmr.language.KmrPascalLanguage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Settings | Editor | Code Style | KMR PascalScript: indent options (a tab shown 4 wide by default). */
public class KmrPascalLanguageCodeStyleSettingsProvider extends LanguageCodeStyleSettingsProvider
{

	@Override
	public @NotNull Language getLanguage()
	{
		return KmrPascalLanguage.INSTANCE;
	}

	@Override
	protected void customizeDefaults(@NotNull CommonCodeStyleSettings commonSettings, @NotNull CommonCodeStyleSettings.IndentOptions indentOptions)
	{
		indentOptions.INDENT_SIZE = 4;
		indentOptions.CONTINUATION_INDENT_SIZE = 4;
		indentOptions.TAB_SIZE = 4;
		indentOptions.USE_TAB_CHARACTER = true;
	}

	@Override
	public @Nullable IndentOptionsEditor getIndentOptionsEditor()
	{
		return new IndentOptionsEditor(this);
	}

	@Override
	public @NotNull CodeStyleConfigurable createConfigurable(@NotNull CodeStyleSettings baseSettings, @NotNull CodeStyleSettings modelSettings)
	{
		return new CodeStyleAbstractConfigurable(baseSettings, modelSettings, "KMR PascalScript")
		{
			@Override
			protected @NotNull CodeStyleAbstractPanel createPanel(@NotNull CodeStyleSettings settings)
			{
				return new TabbedLanguageCodeStylePanel(KmrPascalLanguage.INSTANCE, getCurrentSettings(), settings)
				{
					@Override
					protected void initTabs(CodeStyleSettings settings)
					{
						addIndentOptionsTab(settings);
					}
				};
			}
		};
	}

	@Override
	public @Nullable String getCodeSample(@NotNull SettingsType settingsType)
	{
		return """
			type
			  TRec = record
			    X, Y: Integer;
			  end;

			var
			  Counter: Integer;

			procedure OnTick;
			var
			  I: Integer;
			begin
			  for I := 0 to 3 do
			  begin
			    Counter := Counter + I * 2;
			    if Counter > 10 then
			      Actions.ShowMsg(-1, 'Counter: ' + IntToStr(Counter))
			    else
			      Counter := 0;
			  end;
			  case Counter of
			    0: Exit;
			    1, 2:
			      Counter := 3;
			  end;
			end;
			""";
	}

}
