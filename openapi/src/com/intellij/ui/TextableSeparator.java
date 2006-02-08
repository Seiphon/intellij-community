package com.intellij.ui;

import javax.swing.*;
import java.awt.*;

/**
 * @author cdr
 */
public class TextableSeparator extends JPanel {
  private final JLabel myLabel;

  public TextableSeparator() {
    setLayout(new GridBagLayout());
    myLabel = new JLabel();
    add(myLabel, new GridBagConstraints(0,0,1,0,0,0,GridBagConstraints.WEST, GridBagConstraints.NONE, new Insets(0,5,0,5), 0,0));
    JSeparator separator = new JSeparator(SwingConstants.HORIZONTAL);
    add(separator, new GridBagConstraints(1,0,GridBagConstraints.REMAINDER,0,1,0,GridBagConstraints.CENTER, GridBagConstraints.HORIZONTAL, new Insets(0,0,0,5), 0,0));
  }

  public String getText() {
    return myLabel.getText();
  }
  public void setText(String text) {
    myLabel.setText(text);
  }
}
