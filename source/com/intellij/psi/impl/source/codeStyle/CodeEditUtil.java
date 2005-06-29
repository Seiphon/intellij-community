package com.intellij.psi.impl.source.codeStyle;

import com.intellij.codeFormatting.general.FormatterUtil;
import com.intellij.lang.ASTNode;
import com.intellij.lang.Language;
import com.intellij.lang.StdLanguages;
import com.intellij.lexer.JavaLexer;
import com.intellij.lexer.Lexer;
import com.intellij.newCodeFormatting.Formatter;
import com.intellij.newCodeFormatting.FormattingModel;
import com.intellij.newCodeFormatting.FormattingModelBuilder;
import com.intellij.newCodeFormatting.IndentInfo;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.LanguageFileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.TextRange;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CodeStyleSettingsManager;
import com.intellij.psi.impl.source.SourceTreeToPsiMap;
import com.intellij.psi.impl.source.parsing.ParseUtil;
import com.intellij.psi.impl.source.tree.*;
import com.intellij.psi.tree.IChameleonElementType;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.java.IJavaElementType;
import com.intellij.util.CharTable;
import com.intellij.util.containers.HashMap;

import java.util.*;

import org.jetbrains.annotations.NotNull;

public class CodeEditUtil {

  private static final Logger LOG = Logger.getInstance("#com.intellij.psi.impl.source.codeStyle.CodeEditUtil");
  public static final Key<IndentInfo> INDENT_INFO_KEY = new Key<IndentInfo>("IndentInfo");

  public static ASTNode addChild(CompositeElement parent, ASTNode child, ASTNode anchorBefore) {
    return addChildren(parent, child, child, anchorBefore);
  }

  public static TreeElement addChildren(CompositeElement parent, ASTNode first, ASTNode last, ASTNode anchorBefore) {

    LOG.assertTrue(first != null);

    checkAllWhiteSpaces(parent);

    ASTNode lastChild = last != null ? last.getTreeNext() : null;


    if (Formatter.getInstance().isActive()) {
      parent.addChildren(first, lastChild, anchorBefore);
      return (TreeElement)first;
    } else {
      return addChildrenAndAdjustWhiteSpaces(first, lastChild, anchorBefore, parent);
    }
  }

  private static TreeElement addChildrenAndAdjustWhiteSpaces(ASTNode first,
                                                             final ASTNode lastChild,
                                                             final ASTNode anchorBefore,
                                                             final CompositeElement parent) {
    final ArrayList<PsiElement> dirtyElements = new ArrayList<PsiElement>();
    final List<ASTNode> treePrev;
    try {
      ASTNode nextElement = saveIndentsBeforeAdd(first, lastChild, anchorBefore, parent,dirtyElements);

      first = trimWhiteSpaces(first, lastChild);

      ASTNode lastAdded = findLastAdded(first, lastChild);
      boolean keepLastLineBreaks = containsWhiteSpacesOnly(lastAdded) && lastAdded.textContains('\n');

      if (first == null) {
        return null;
      }

      boolean keepFirstIndent = keepFirstIndent(parent, anchorBefore, first, lastChild);

      parent.addChildren(first, lastChild, anchorBefore);
      checkAllTrees(parent.getPsi().getContainingFile());

      treePrev = getPreviousElements(first);
      if (!FormatterUtil.join(TreeUtil.prevLeaf(first), first)){
        adjustWhiteSpaces(first, anchorBefore, keepFirstIndent, true);
      }

      if (nextElement != null) {
        if (!FormatterUtil.join(TreeUtil.prevLeaf(nextElement), nextElement)) {
          adjustWhiteSpaces(nextElement, nextElement.getTreeNext(), false, keepLastLineBreaks);
        }
      }
    }
    finally {
      clearIndentInfo(dirtyElements);
    }

    checkAllWhiteSpaces(parent);

    return returnFirstChangedNode(treePrev, parent);
  }

  @NotNull
  private static ASTNode findLastAdded(final ASTNode first, final ASTNode lastChild) {
    ASTNode result = first;
    ASTNode current = first;
    while (current != null && current.getTreeNext() != lastChild) {
      current = current.getTreeNext();
      if (current == null) return result;
      result = current;
    }
    return result;
  }

  private static void checkAllTrees(final PsiFile containingFile) {
    final PsiFile[] psiRoots = containingFile.getPsiRoots();
    for (int i = 0; i + 1 < psiRoots.length; i++) {
      LOG.assertTrue(psiRoots[i].getText().equals(psiRoots[i+1].getText()));
    }
  }

  private static boolean keepFirstIndent(final CompositeElement parent,
                                         final ASTNode anchorBefore,
                                         final ASTNode first,
                                         final ASTNode lastChild) {
    boolean keepFirstIndent = false;

    final ASTNode elemBeforeAnchor = getElementBeforeAnchor(parent, anchorBefore);

    if (elemBeforeAnchor != null) {
      ASTNode firstNonEmpty = findFirstNonEmpty(first, lastChild, parent, anchorBefore);
      if (firstNonEmpty == null || mustKeepFirstIndent(elemBeforeAnchor, parent)){
        keepFirstIndent = true;
      }
    }
    return keepFirstIndent;
  }

  private static TreeElement returnFirstChangedNode(final List<ASTNode> treePrev, final CompositeElement parent) {
    if (treePrev == null) {
      return (TreeElement)parent.getFirstChildNode();
    } else {
      ASTNode firstValid = findFirstValid(treePrev);
      if (firstValid == null) {
        return (TreeElement)parent.getFirstChildNode();
      } else {
        return (TreeElement)firstValid.getTreeNext();
      }
    }
  }

  private static ASTNode saveIndentsBeforeAdd(final ASTNode first,
                                              final ASTNode lastChild,
                                              final ASTNode anchorBefore,
                                              final CompositeElement parent,
                                              final Collection<PsiElement> dirtyElements) {
    saveIndents(first, lastChild, dirtyElements);
    ASTNode nextElement = anchorBefore;
    if (nextElement == null) {
      nextElement = findElementAfter(parent, false);
    } else if (isWS(nextElement) || nextElement.getTextLength() == 0) {
      nextElement = findElementAfter(anchorBefore, false);
    }
    if (nextElement != null) {
      saveIndents(nextElement, dirtyElements);
    }
    return nextElement;
  }

  private static ASTNode findFirstNonEmpty(final ASTNode first,
                                           final ASTNode last,
                                           final CompositeElement parent,
                                           final ASTNode anchorBefore) {
    ASTNode firstNonEmpty = first;
    while (firstNonEmpty != null && firstNonEmpty != last && firstNonEmpty.getTextLength() == 0) {
      firstNonEmpty = firstNonEmpty.getTreeNext();
    }
    if (firstNonEmpty == null && anchorBefore != null) {
      firstNonEmpty = TreeUtil.findFirstLeaf(anchorBefore);
      while (firstNonEmpty != null && firstNonEmpty.getTextLength() == 0) {
        firstNonEmpty = firstNonEmpty.getTreeNext();
      }
    }
    if (firstNonEmpty == null) {
      firstNonEmpty = TreeUtil.nextLeaf(parent);
    }
    return firstNonEmpty;
  }

  private static boolean mustKeepFirstIndent(final ASTNode elementBeforeChange, final CompositeElement parent) {
    if (elementBeforeChange == null) return true;
    if (elementBeforeChange.getElementType() != ElementType.WHITE_SPACE) {
      return false;
    }
    return elementBeforeChange.getTextRange().getStartOffset() < parent.getTextRange().getStartOffset();
  }

  private static ASTNode getElementBeforeAnchor(final CompositeElement parent, final ASTNode anchorBefore) {
    if (anchorBefore != null) {
      return TreeUtil.prevLeaf(anchorBefore);
    }

    final ASTNode lastChild = parent.getLastChildNode();
    if (lastChild != null) {
      if (lastChild.getTextLength() > 0) {
        return lastChild;
      } else {
        return TreeUtil.prevLeaf(lastChild);
      }
    } else {
      return TreeUtil.prevLeaf(parent);
    }

  }

  private static void checkAllWhiteSpaces(final ASTNode parent) {
    LOG.assertTrue(parent.getPsi().isValid());
    ASTNode node = parent.getPsi().getContainingFile().getNode();
    ASTNode leaf = TreeUtil.findFirstLeaf(node);
    while (leaf != null) {
      if (isInvalidWhiteSpace(leaf)) {
        LOG.assertTrue(false);
      }
      leaf = TreeUtil.nextLeaf(leaf);
    }
  }

  public static void removeChild(CompositeElement parent, ASTNode child) {
    removeChildren(parent, child, child);
  }

  public static void removeChildren(CompositeElement parent, ASTNode first, ASTNode last) {
    checkAllWhiteSpaces(parent);
    if (Formatter.getInstance().isActive()) {
      parent.removeRange(first, findLastChild(last));
    } else {
      removeChildrenAndAdjustWhiteSpaces(first, last, parent);
    }

    checkAllWhiteSpaces(parent);
  }

  private static void removeChildrenAndAdjustWhiteSpaces(final ASTNode first, final ASTNode last, final CompositeElement parent) {
    ASTNode lastChild = findLastChild(last);
    final ASTNode prevElement = TreeUtil.prevLeaf(first);
    ASTNode nextElement = findElementAfter(last == null ? parent : last, false);
    final ArrayList<PsiElement> dirtyElements = new ArrayList<PsiElement>();
    if (nextElement != null) {
      saveIndents(nextElement, dirtyElements);
    }

    try {
      boolean adjustWSBefore = containLineBreaks(first, lastChild);

      if (!mustKeepFirstIndent(prevElement, parent)) {
        adjustWSBefore = true;
      }

      parent.removeRange(first, lastChild);

      if (!adjustWSBefore && parent.getTextLength() == 0 && prevElement != null && isWS(prevElement) && !prevElement.textContains('\n')) {
        adjustWSBefore = true;
      }

      final PsiFile file = parent.getPsi().getContainingFile();
      final CodeStyleSettings.IndentOptions options = CodeStyleSettingsManager.getSettings(file.getProject())
        .getIndentOptions(file.getFileType());

      if (nextElement != null) {

        adjustSpacePositions(nextElement, prevElement, options);

        if (prevElement != null) {
          FormatterUtil.join(prevElement, TreeUtil.nextLeaf(prevElement));
        }

        if (!nextElement.getPsi().isValid()) {
          nextElement = findElementAfter(last == null ? parent : last, false);
        }

        if (nextElement != null && adjustWSBefore) {
          adjustWhiteSpaceBefore(nextElement, true, true, true, false);
        }

      } else {
        final ASTNode fileNode = SourceTreeToPsiMap.psiElementToTree(parent.getPsi().getContainingFile());
        ASTNode lastLeaf = TreeUtil.findLastLeaf(fileNode);
        if (isWS(lastLeaf)) {
          delete(lastLeaf);
        }
      }
    }
    finally {
      clearIndentInfo(dirtyElements);
    }
  }

  private static ASTNode findLeafAfter(final ASTNode astNode) {
    ASTNode value = TreeUtil.nextLeaf(astNode);
    while (value != null && value.getElementType() == ElementType.WHITE_SPACE) {
      value = TreeUtil.nextLeaf(value);
    }
    return value;
  }

  private static ASTNode findLastChild(final ASTNode last) {
    return last == null ? null : last.getTreeNext();
  }

  private static void adjustSpacePositions(final ASTNode nextElement,
                                           final ASTNode prevElement,
                                           final CodeStyleSettings.IndentOptions options) {
    ASTNode elementBeforeNext = TreeUtil.prevLeaf(nextElement);

    if (prevElement != null && isWS(prevElement) && isWS(elementBeforeNext) && prevElement != elementBeforeNext) {
      replaceTwoSpacesWithOne(elementBeforeNext, prevElement, options);
    }

    elementBeforeNext = TreeUtil.prevLeaf(nextElement);

    if (isInvalidWhiteSpace(elementBeforeNext)) {
      replaceInvalidWhiteSpace(elementBeforeNext);
    }

    elementBeforeNext = TreeUtil.prevLeaf(nextElement);

    if (elementBeforeNext != null && isWS(elementBeforeNext) && elementBeforeNext.getTextRange().getStartOffset() == 0) {
      final IndentInfo info = createInfo(elementBeforeNext.getText(), options);
      if (info.getLineFeeds() > 0) {
        final String text = new IndentInfo(0,info.getIndentSpaces(), info.getSpaces()).generateNewWhiteSpace(options);
        replace(elementBeforeNext, text);
      }
    }
  }

  private static void replaceTwoSpacesWithOne(final ASTNode elementBeforeNext,
                                              final ASTNode prevElement,
                                              final CodeStyleSettings.IndentOptions options) {
    if (!canModifyWS(elementBeforeNext.getPsi().getContainingFile())) {
      final String text = prevElement.getText() + elementBeforeNext.getText();
      delete(elementBeforeNext);
      replace(prevElement, text);
    }
    else if (!elementBeforeNext.textContains('\n') && prevElement.textContains('\n')) {
      /*
          void foo1(){}
          static void foo2(){}
          remove static
      */
      delete(elementBeforeNext);
    } else {
      final String text = composeNewWS(prevElement.getText(), elementBeforeNext.getText(), options);
      delete(elementBeforeNext);
      replace(prevElement, text);
    }
  }

  private static boolean canModifyWS(final PsiFile containingFile) {
    return containingFile.getLanguage() != StdLanguages.XHTML;
  }

  private static void replaceInvalidWhiteSpace(final ASTNode elementBeforeNext) {
    final String text = elementBeforeNext.getText();
    ASTNode nextAnchor = TreeUtil.nextLeaf(elementBeforeNext);
    delete(elementBeforeNext);
    FormatterUtil.replaceWhiteSpace(text,
                                    nextAnchor,
                                    ElementType.WHITE_SPACE, null);
  }

  private static boolean isInvalidWhiteSpace(final ASTNode elementBeforeNext) {
    return isWS(elementBeforeNext) && whiteSpaceHasInvalidPosition(elementBeforeNext);
  }

  private static void replace(final ASTNode element, final String text) {
    if (!text.equals(element.getText())){
      final CharTable charTable = SharedImplUtil.findCharTableByTree(element);
      LeafElement newWhiteSpace = Factory.createSingleLeafElement(element.getElementType(),
                                                                  text.toCharArray(), 0, text.length(),
                                                                  charTable,
                                                                  SharedImplUtil.getManagerByTree(element));

      element.getTreeParent().replaceChild(element,
                                           newWhiteSpace);
    }
  }


  private static boolean hasNonEmptyNext(final ASTNode element) {
    ASTNode current = element.getTreeNext();
    while (current != null) {
      if (current.getElementType() == ElementType.ERROR_ELEMENT) { //TODO check insertMissingTokens
        return true;
      }
      if (current.getTextLength() > 0) {
        return true;
      }
      if (current.getElementType() == ElementType.WHITE_SPACE) {
        return false;
      }
      current = current.getTreeNext();
    }
    if (element.getTextRange().getEndOffset() == element.getPsi().getContainingFile().getTextLength()) {
      return element.getTreeNext() == null;
    }
    return false;
  }

  private static boolean hasNonEmptyPrev(ASTNode element) {
    ASTNode current = element.getTreePrev();
    while (current != null) {
      if (current.getElementType() == ElementType.ERROR_ELEMENT) { //TODO check insertMissingTokens
        return true;
      }
      if (current.getTextLength() > 0) {
        return true;
      }
      if (current.getElementType() == ElementType.WHITE_SPACE) {
        return false;
      }
      current = current.getTreePrev();
    }
    if (element.getTextRange().getStartOffset() == 0) return element.getTreePrev() == null;
    return false;
  }

  private static boolean whiteSpaceHasInvalidPosition(final ASTNode element) {
    final ASTNode treeParent = element.getTreeParent();
    if (isWS(element) && treeParent.getElementType() == ElementType.XML_TEXT) return false;

    if (treeParent.getPsi().getUserData(ParseUtil.UNCLOSED_ELEMENT_PROPERTY) != null) return false;
    if (hasNonEmptyPrev(element) && hasNonEmptyNext(element)) return false;
    if (treeParent.getElementType() == ElementType.XML_PROLOG) return false;
    if (treeParent.getElementType() == ElementType.HTML_DOCUMENT) return false;
    if (treeParent.getElementType() == ElementType.ERROR_ELEMENT) return false;
    if (treeParent.getElementType() instanceof IChameleonElementType) {
      if (((IChameleonElementType)treeParent.getElementType()).isParsable(treeParent.getText(), element.getPsi().getProject())){
        return false;
      } else {
        return true;
      }
    } else {
      return true;
    }
  }

  private static boolean containLineBreaks(final ASTNode first, final ASTNode last) {
    ASTNode current = first;
    while (current != last) {
      if (current.textContains('\n')) return true;
      current = current.getTreeNext();
    }
    return false;
  }

  private static void saveIndents(final ASTNode first, final ASTNode lastChild, final Collection<PsiElement> dirtyElements) {
    final PsiFile file = first.getPsi().getContainingFile();
    final Language language = file.getLanguage();
    final FormattingModelBuilder builder = language.getFormattingModelBuilder();
    if (builder != null) {
      final CodeStyleSettings settings = CodeStyleSettingsManager.getSettings(file.getProject());
      final FormattingModel model = builder.createModel(file, settings);
      ASTNode current = first;
      while (current != lastChild) {
        final IElementType elementType = current.getElementType();
        if (elementType != ElementType.WHITE_SPACE
            && elementType != ElementType.DOC_COMMENT_LEADING_ASTERISKS
            && elementType != ElementType.DOC_TAG
            && current.getTextLength() > 0) {
          Formatter.getInstance().saveIndents(model, current.getTextRange(),
                                              new MyIndentInfoStorage(file, dirtyElements),
                                              settings,
                                              settings.getIndentOptions(file.getFileType()));
        }
        current = current.getTreeNext();
      }

    }
  }

  private static ASTNode trimWhiteSpaces(final ASTNode first, final ASTNode lastChild) {
    ASTNode current = first;
    ASTNode result = first;
    while(current != null && current != lastChild) {

      if (current.getElementType() != ElementType.XML_TEXT) {
        LeafElement leaf = TreeUtil.findFirstLeaf(current);

        if (((current == first && current.getTreeNext() != lastChild)
             || current.getElementType() != ElementType.WHITE_SPACE)) {
          if (leaf != null && leaf.getElementType() == ElementType.WHITE_SPACE) {
            if (leaf == result) result = leaf.getTreeNext();
            delete(leaf);
          }
        }
        leaf = TreeUtil.findLastLeaf(current);
        if (current.getElementType() != ElementType.WHITE_SPACE) {
          if (leaf != null && leaf.getElementType() == ElementType.WHITE_SPACE) {
            if (leaf == result) result = leaf.getTreeNext();
            delete(leaf);
          }
        }
      }
      current = current.getTreeNext();
    }
    return result;
  }

  private static ASTNode findFirstValid(final List<ASTNode> treePrev) {
    for (Iterator<ASTNode> iterator = treePrev.iterator(); iterator.hasNext();) {
      ASTNode treeElement = iterator.next();
      if (treeElement.getPsi().isValid()) return treeElement;
    }
    return null;
  }

  private static List<ASTNode> getPreviousElements(final ASTNode first) {
    ASTNode current = first.getTreePrev();
    if (current == null) return null;
    final ArrayList<ASTNode> result = new ArrayList<ASTNode>();
    while (current != null) {
      result.add(current);
      current = current.getTreePrev();
    }
    return result;
  }

  private static void adjustWhiteSpaces(final ASTNode first,
                                        final ASTNode last,
                                        final boolean keepFirstIndent,
                                        final boolean keepLineBreaks) {
    ASTNode current = first;
    while (current != null && current != last) {
      if (notIsSpace(current)) {
        adjustWhiteSpaceBefore(current, true, keepLineBreaks, !keepFirstIndent || current != first, true);
      }
      current = current.getTreeNext();
    }
  }

  private static boolean notIsSpace(final ASTNode current) {
    return current.getElementType() != ElementType.WHITE_SPACE && current.getText().trim().length() > 0;
  }

  private static void adjustWhiteSpaceBefore(final ASTNode first,
                                             final boolean keepBlankLines,
                                             final boolean keepLineBreaks,
                                             final boolean changeWSBeforeFirstElement, final boolean changeLineFeedsBeforeFirstElement) {
    CodeStyleSettings settings = CodeStyleSettingsManager.getSettings(first.getPsi().getProject());
    final PsiElement psi = first.getPsi();
    final PsiFile file = psi.getContainingFile();
    final FormattingModelBuilder builder = file.getLanguage().getFormattingModelBuilder();
    final FormattingModelBuilder elementBuilder = psi.getLanguage().getFormattingModelBuilder();

    final boolean keepWhiteSpaces = settings.HTML_KEEP_WHITESPACES;
    if (!canModifyWS(file)) {
      settings.HTML_KEEP_WHITESPACES = true;
    }

    try {
      if (builder != null && elementBuilder != null) {
        ASTNode firstNonSpaceLeaf = TreeUtil.findFirstLeaf(first);
        while (firstNonSpaceLeaf != null && firstNonSpaceLeaf.getElementType() == ElementType.WHITE_SPACE) {
          firstNonSpaceLeaf = TreeUtil.nextLeaf(firstNonSpaceLeaf);
        }
        if (firstNonSpaceLeaf != null) {
          final int startOffset = firstNonSpaceLeaf.getStartOffset();
          final int endOffset = first.getTextRange().getEndOffset();
          if (startOffset < endOffset) {
            Formatter.getInstance().adjustTextRange(builder.createModel(file, settings), settings,
                                                    settings.getIndentOptions(file.getFileType()),
                                                    new TextRange(startOffset, endOffset),
                                                    keepBlankLines,
                                                    keepLineBreaks,
                                                    changeWSBeforeFirstElement,
                                                    changeLineFeedsBeforeFirstElement, new MyIndentInfoStorage(file, null));
          }
        }
      }
    }
    finally {
      settings.HTML_KEEP_WHITESPACES = keepWhiteSpaces;
    }
  }


  private static String composeNewWS(final String firstText,
                                     final String secondText,
                                     final CodeStyleSettings.IndentOptions options) {
    IndentInfo first = createInfo(firstText, options);
    IndentInfo second = createInfo(secondText, options);
    final int lineFeeds = Math.max(first.getLineFeeds(), second.getLineFeeds());
    return new IndentInfo(lineFeeds,
                          0,
                          second.getSpaces()).generateNewWhiteSpace(options);
  }

  private static IndentInfo createInfo(final String text, final CodeStyleSettings.IndentOptions options) {
    int lf = 0;
    int spaces = 0;
    for (int i  = 0; i < text.length(); i++) {
      final char c = text.charAt(i);
      switch(c) {
        case ' ': spaces++; break;
        case '\t': spaces += options.TAB_SIZE; break;
        case '\n': lf++; spaces = 0; break;
      }
    }
    return new IndentInfo(lf, 0, spaces);
  }

  private static void delete(final ASTNode prevElement) {
    FormatterUtil.delete(prevElement);
  }

  private static ASTNode findElementAfter(final ASTNode parent, boolean canBeWhiteSpace) {
    ASTNode candidate = parent.getTreeNext();
    while (candidate !=null) {
      if ((canBeWhiteSpace || !isWS(candidate) && !containsWhiteSpacesOnly(candidate)) && candidate.getTextLength() > 0) return candidate;
      candidate =  candidate.getTreeNext();
    }
    final ASTNode treeParent = parent.getTreeParent();
    if (treeParent != null) {
      return findElementAfter(treeParent, canBeWhiteSpace);
    } else {
      return null;
    }
  }

  private static boolean containsWhiteSpacesOnly(final ASTNode candidate) {
    if (candidate.getTextLength() == 0) return false;
    ASTNode child = candidate.getFirstChildNode();
    if (child == null) return false;
    while (child != null) {
      if (!isWS(child)) {
        if (!containsWhiteSpacesOnly(child)) {
          return false;
        }
      }
      child =  child.getTreeNext();
    }
    return true;
  }

  private static boolean isWS(final ASTNode lastChild) {
    if (lastChild == null)return false;
    return lastChild.getElementType() == ElementType.WHITE_SPACE;
  }

  public static ASTNode replaceChild(CompositeElement parent, ASTNode oldChild, ASTNode newChild) {
    checkAllWhiteSpaces(parent);

    if (Formatter.getInstance().isActive()) {
      parent.replaceChild(oldChild, newChild);
      return newChild;
    } else {
      return replaceAndAdjustWhiteSpaces(oldChild, newChild, parent);
    }
  }

  private static ASTNode replaceAndAdjustWhiteSpaces(final ASTNode oldChild, final ASTNode newChild, final CompositeElement parent) {
    final ASTNode elementAfter = findElementAfter(oldChild, true);

    boolean changeFirstWS = newChild.textContains('\n') || oldChild.textContains('\n');

    ASTNode firstNonEmpty = findFirstNonEmpty(newChild, newChild.getTreeNext(), parent, newChild.getTreeNext());

    if (!canStickChildrenTogether(TreeUtil.prevLeaf(oldChild), firstNonEmpty)) {
      changeFirstWS = true;
    }

    Collection<PsiElement> dirtyElements = new ArrayList<PsiElement>();

    if (changeFirstWS) {
      saveIndents(newChild, dirtyElements);
    }
    if (elementAfter != null && !isWS(elementAfter)) {
      saveIndents(elementAfter, dirtyElements);
    }

    try {
      boolean checkWhiteSpaces = oldChild.getTextLength() > 0 && newChild.getTextLength() == 0;

      parent.replaceChild(oldChild, newChild);

      adjustWSPositionsAfterReplacement(checkWhiteSpaces, newChild, parent);

      final List<ASTNode> treePrev = getPreviousElements(newChild);

      adjustWhiteSpaceBefore(newChild, true, false, changeFirstWS, false);
      if (elementAfter != null && !isWS(elementAfter)) {
        adjustWhiteSpaceBefore(elementAfter, true, true, true, false);
      }

      checkAllWhiteSpaces(parent);
      return returnFirstChangedNode(treePrev, parent);
    }
    finally {
      clearIndentInfo(dirtyElements);
    }
  }

  private static void clearIndentInfo(final Collection<PsiElement> dirtyElements) {
    for (Iterator<PsiElement> iterator = dirtyElements.iterator(); iterator.hasNext();) {
      iterator.next().putUserData(INDENT_INFO_KEY, null);
    }
  }

  private static void adjustWSPositionsAfterReplacement(final boolean checkWhiteSpaces,
                                                        final ASTNode newChild,
                                                        final CompositeElement parent) {
    if (checkWhiteSpaces) {
      final ASTNode nextLeaf = TreeUtil.nextLeaf(newChild);
      final ASTNode prevLeaf = TreeUtil.nextLeaf(newChild);

      if (isWS(nextLeaf) && isWS(prevLeaf)) {
        final PsiFile file = parent.getPsi().getContainingFile();
        final CodeStyleSettings.IndentOptions options = CodeStyleSettingsManager.getSettings(file.getProject())
          .getIndentOptions(file.getFileType());
        replaceTwoSpacesWithOne(prevLeaf, nextLeaf, options);
      } else if (isInvalidWhiteSpace(prevLeaf)) {
        replaceInvalidWhiteSpace(prevLeaf);

      } else if (isInvalidWhiteSpace(nextLeaf)) {
        replaceInvalidWhiteSpace(nextLeaf);
      }
    }
  }

  private static void saveIndents(final ASTNode newChild, final Collection<PsiElement> dirtyElements) {
    saveIndents(newChild, newChild.getTreeNext(), dirtyElements);
  }

  public static boolean canStickChildrenTogether(final ASTNode child1, final ASTNode child2) {
    if (child1 == null || child2 == null) return true;
    if (isWS(child1) || isWS(child2)) return true;

    ASTNode token1 = TreeUtil.findLastLeaf(child1);
    ASTNode token2 = TreeUtil.findFirstLeaf(child2);

    LOG.assertTrue(token1 != null);
    LOG.assertTrue(token2 != null);

    if (token1.getElementType() instanceof IJavaElementType && token2.getElementType() instanceof IJavaElementType) {
      return canStickJavaTokens((PsiJavaToken)SourceTreeToPsiMap.treeElementToPsi(token1),
                                (PsiJavaToken)SourceTreeToPsiMap.treeElementToPsi(token2));
    }
    else {
      return true; //?
    }

  }

  private static Map<Pair<IElementType, IElementType>, Boolean> myCanStickJavaTokensMatrix = new HashMap<Pair<IElementType, IElementType>, Boolean>();

  public static boolean canStickJavaTokens(PsiJavaToken token1, PsiJavaToken token2) {
    IElementType type1 = token1.getTokenType();
    IElementType type2 = token2.getTokenType();

    Pair<IElementType, IElementType> pair = new Pair<IElementType, IElementType>(type1, type2);
    Boolean res = myCanStickJavaTokensMatrix.get(pair);
    if (res == null) {
      if (!checkToken(token1) || !checkToken(token2)) return true;
      String text = token1.getText() + token2.getText();
      Lexer lexer = new JavaLexer(LanguageLevel.HIGHEST);
      lexer.start(text.toCharArray(), 0, text.length());
      boolean canMerge = lexer.getTokenType() == type1;
      lexer.advance();
      canMerge &= lexer.getTokenType() == type2;
      res = Boolean.valueOf(canMerge);
      myCanStickJavaTokensMatrix.put(pair, res);
    }
    return res.booleanValue();
  }

  private static boolean checkToken(final PsiJavaToken token1) {
    Lexer lexer = new JavaLexer(LanguageLevel.HIGHEST);
    final String text = token1.getText();
    lexer.start(text.toCharArray(), 0, text.length());
    if (lexer.getTokenType() != token1.getTokenType()) return false;
    lexer.advance();
    return lexer.getTokenType() == null;
  }

  public static ASTNode getDefaultAnchor(PsiImportList list, PsiImportStatementBase statement) {
    CodeStyleSettings settings = CodeStyleSettingsManager.getSettings(list.getProject());
    ImportHelper importHelper = new ImportHelper(settings);
    return importHelper.getDefaultAnchor(list, statement);
  }

  private static final HashMap<String, Integer> ourModifierToOrderMap = new HashMap<String, Integer>();

  static { //TODO : options?
    ourModifierToOrderMap.put(PsiModifier.PUBLIC, new Integer(1));
    ourModifierToOrderMap.put(PsiModifier.PRIVATE, new Integer(1));
    ourModifierToOrderMap.put(PsiModifier.PROTECTED, new Integer(1));
    ourModifierToOrderMap.put(PsiModifier.NATIVE, new Integer(2));
    ourModifierToOrderMap.put(PsiModifier.STATIC, new Integer(3));
    ourModifierToOrderMap.put(PsiModifier.ABSTRACT, new Integer(3));
    ourModifierToOrderMap.put(PsiModifier.FINAL, new Integer(4));
    ourModifierToOrderMap.put(PsiModifier.SYNCHRONIZED, new Integer(5));
    ourModifierToOrderMap.put(PsiModifier.TRANSIENT, new Integer(5));
    ourModifierToOrderMap.put(PsiModifier.VOLATILE, new Integer(5));
    ourModifierToOrderMap.put(PsiModifier.STRICTFP, new Integer(6));
  }

  public static ASTNode getDefaultAnchor(PsiModifierList modifierList, PsiKeyword modifier) {
    Integer order = ourModifierToOrderMap.get(modifier.getText());
    if (order == null) return null;
    for (ASTNode child = SourceTreeToPsiMap.psiElementToTree(modifierList).getFirstChildNode();
         child != null;
         child = child.getTreeNext()) {
      if (ElementType.KEYWORD_BIT_SET.isInSet(child.getElementType())) {
        Integer order1 = ourModifierToOrderMap.get(child.getText());
        if (order1 == null) continue;
        if (order1.intValue() > order.intValue()) {
          return child;
        }
      }
    }
    return null;
  }

  public static PsiElement getDefaultAnchor(PsiClass aClass, PsiMember member) {
    CodeStyleSettings settings = CodeStyleSettingsManager.getSettings(aClass.getProject());

    int order = getMemberOrderWeight(member, settings);
    if (order < 0) return null;

    PsiElement lastMember = null;
    for (PsiElement child = aClass.getFirstChild(); child != null; child = child.getNextSibling()) {
      int order1 = getMemberOrderWeight(child, settings);
      if (order1 < 0) continue;
      if (order1 > order) {
        if (lastMember != null) {
          PsiElement nextSibling = lastMember.getNextSibling();
          while (nextSibling instanceof PsiJavaToken &&
                 (nextSibling.getText().equals(",") || nextSibling.getText().equals(";"))) {
            nextSibling = nextSibling.getNextSibling();
          }
          return nextSibling == null ? aClass.getLBrace().getNextSibling() : nextSibling;
        }
        else {
          return aClass.getLBrace().getNextSibling();
        }
      }
      lastMember = child;
    }
    return aClass.getRBrace();
  }

  private static int getMemberOrderWeight(PsiElement member, CodeStyleSettings settings) {
    if (member instanceof PsiField) {
      return member instanceof PsiEnumConstant ? 1 : settings.FIELDS_ORDER_WEIGHT + 1;
    }
    else if (member instanceof PsiMethod) {
      return ((PsiMethod)member).isConstructor() ? settings.CONSTRUCTORS_ORDER_WEIGHT + 1 : settings.METHODS_ORDER_WEIGHT + 1;
    }
    else if (member instanceof PsiClass) {
      return settings.INNER_CLASSES_ORDER_WEIGHT + 1;
    }
    else {
      return -1;
    }
  }

  public static String getStringWhiteSpaceBetweenTokens(ASTNode first, ASTNode second, Language language) {
    final FormattingModelBuilder modelBuilder = language.getFormattingModelBuilder();
    if (modelBuilder == null) {
      final LeafElement leafElement = ParseUtil.nextLeaf((TreeElement)first, null);
      if (leafElement != second) {
        return leafElement.getText();
      }
      else {
        return null;
      }
    }
    else {
      final PsiElement secondAsPsiElement = SourceTreeToPsiMap.treeElementToPsi(second);
      LOG.assertTrue(secondAsPsiElement != null);
      final PsiFile file = secondAsPsiElement.getContainingFile();
      final Project project = secondAsPsiElement.getProject();
      final CodeStyleSettings settings = CodeStyleSettingsManager.getInstance(project).getCurrentSettings();
      return getWhiteSpaceBeforeToken(second, language, true).generateNewWhiteSpace(settings.getIndentOptions(file.getFileType()));
    }

  }

  public static IndentInfo getIndentWhiteSpaceBeforeToken(final ASTNode tokenNode,
                                                          final Language language) {
    return getWhiteSpaceBeforeToken(tokenNode, chooseLanguage(tokenNode, language), false);
  }

  private static Language chooseLanguage(final ASTNode tokenNode, final Language language) {
    if (tokenNode == null) return language;
    final PsiElement secondAsPsiElement = SourceTreeToPsiMap.treeElementToPsi(tokenNode);
    if (secondAsPsiElement == null) return language;
    final PsiFile file = secondAsPsiElement.getContainingFile();
    if (file == null) return language;
    final FileType fileType = file.getFileType();
    if (!(fileType instanceof LanguageFileType)) {
      return language;
    }

    if (((LanguageFileType)fileType).getLanguage().getFormattingModelBuilder() == null) {
      return language;
    } else {
      return ((LanguageFileType)fileType).getLanguage();
    }

  }

  public static IndentInfo getWhiteSpaceBeforeToken(final ASTNode tokenNode,
                                                    final Language language,
                                                    final boolean mayChangeLineFeeds) {
    LOG.assertTrue(tokenNode != null);
    final PsiElement secondAsPsiElement = SourceTreeToPsiMap.treeElementToPsi(tokenNode);
    LOG.assertTrue(secondAsPsiElement != null);
    final PsiFile file = secondAsPsiElement.getContainingFile();
    final Project project = secondAsPsiElement.getProject();
    final CodeStyleSettings settings = CodeStyleSettingsManager.getInstance(project).getCurrentSettings();
    final int tokenStartOffset = tokenNode.getStartOffset();

    final boolean oldValue = settings.XML_KEEP_LINE_BREAKS;
    final int oldKeepBlankLines = settings.XML_KEEP_BLANK_LINES;
    settings.XML_KEEP_BLANK_LINES = 0;
    try {
      final FormattingModelBuilder builder = language.getFormattingModelBuilder();
      final PsiElement element = file.findElementAt(tokenStartOffset);

      if (builder != null && element.getLanguage().getFormattingModelBuilder() != null) {

        final TextRange textRange = element.getTextRange();
        final FormattingModel model = builder.createModel(file, settings);
        return Formatter.getInstance().getWhiteSpaceBefore(model.getDocumentModel(),
                                                           model.getRootBlock(),
                                                           settings, settings.getIndentOptions(file.getFileType()), textRange,
                                                           mayChangeLineFeeds);
      } else {
        return new IndentInfo(0, 0, 0);
      }

    }
    finally {
      settings.XML_KEEP_LINE_BREAKS = oldValue;
      settings.XML_KEEP_BLANK_LINES = oldKeepBlankLines;
    }
  }

  private static class MyIndentInfoStorage implements Formatter.IndentInfoStorage {
    private final PsiFile myFile;
    private final Collection<PsiElement> myDirtyElements;

    public MyIndentInfoStorage(final PsiFile file, final Collection<PsiElement> dirtyElement) {
      myFile = file;
      myDirtyElements = dirtyElement;
    }

    public void saveIndentInfo(IndentInfo info, int startOffset) {
      final PsiElement element = myFile.findElementAt(startOffset);
      if (element != null) {
        element.putUserData(INDENT_INFO_KEY, info);
        myDirtyElements.add(element);
      }
    }

    public IndentInfo getIndentInfo(int startOffset) {
      return myFile.findElementAt(startOffset).getUserData(INDENT_INFO_KEY);
    }
  }
}
