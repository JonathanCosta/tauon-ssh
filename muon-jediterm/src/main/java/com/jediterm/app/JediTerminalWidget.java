package com.jediterm.app;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.util.Disposer;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.ui.JediTermWidget;
import com.jediterm.terminal.ui.TerminalPanel;
import com.jediterm.terminal.ui.settings.SettingsProvider;
import org.jetbrains.annotations.NotNull;

public class JediTerminalWidget extends JediTermWidget implements Disposable {

  public JediTerminalWidget(SettingsProvider settingsProvider, Disposable parent) {
    super(settingsProvider);
    setName("terminal");

    Disposer.register(parent, this);
  }

  @Override
  protected TerminalPanel createTerminalPanel(@NotNull SettingsProvider settingsProvider,
                                                  @NotNull StyleState styleState,
                                                  @NotNull TerminalTextBuffer textBuffer) {
    TerminalPanel panel = new TerminalPanel(settingsProvider, textBuffer, styleState);
    //Disposer.register(this, panel);
    return panel;
  }

  @Override
  public void dispose() {
  }
}
