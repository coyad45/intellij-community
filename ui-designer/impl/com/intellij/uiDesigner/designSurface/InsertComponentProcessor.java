package com.intellij.uiDesigner.designSurface;

import com.intellij.CommonBundle;
import com.intellij.ide.palette.impl.PaletteManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.VariableKind;
import com.intellij.uiDesigner.*;
import com.intellij.uiDesigner.compiler.CodeGenerationException;
import com.intellij.uiDesigner.compiler.Utils;
import com.intellij.uiDesigner.core.Util;
import com.intellij.uiDesigner.make.PsiNestedFormLoader;
import com.intellij.uiDesigner.palette.ComponentItem;
import com.intellij.uiDesigner.palette.Palette;
import com.intellij.uiDesigner.quickFixes.CreateFieldFix;
import com.intellij.uiDesigner.radComponents.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;

/**
 * @author Anton Katilin
 * @author Vladimir Kondratyev
 */
public final class InsertComponentProcessor extends EventProcessor {
  private static final Logger LOG = Logger.getInstance("#com.intellij.uiDesigner.designSurface.InsertComponentProcessor");

  private final GuiEditor myEditor;
  private boolean mySticky;
  private RadContainer myTargetContainer;
  private RadComponent myInsertedComponent;
  private Point myInitialPoint;
  private Dimension myInitialSize;
  private boolean myShouldSetPreferredSizeIfNotResized;
  private GridInsertProcessor myGridInsertProcessor;

  public InsertComponentProcessor(@NotNull final GuiEditor editor) {
    myEditor = editor;
    myGridInsertProcessor = new GridInsertProcessor(editor);
  }

  public boolean isSticky() {
    return mySticky;
  }

  public void setSticky(final boolean sticky) {
    mySticky = sticky;
  }

  protected void processKeyEvent(final KeyEvent e) {}

  /**
   * TODO[vova] it would be fine to configure such "input" controls somewhere in palette
   * @return whether component is an input control or not
   */
  private static boolean isInputComponent(@NotNull final RadComponent component){
    final Class aClass = component.getComponentClass();
    if(
      AbstractButton.class.isAssignableFrom(aClass) ||
      JComboBox.class.isAssignableFrom(aClass) ||
      JList.class.isAssignableFrom(aClass) ||
      JSpinner.class.isAssignableFrom(aClass) ||
      JTabbedPane.class.isAssignableFrom(aClass) ||
      JTable.class.isAssignableFrom(aClass) ||
      JTextComponent.class.isAssignableFrom(aClass) ||
      JTree.class.isAssignableFrom(aClass)
    ){
      return true;
    }

    return false;
  }

  @NotNull
  private static String suggestBinding(final GuiEditor editor, @NotNull final String componentClassName){
    final int lastDotIndex = componentClassName.lastIndexOf('.');
    String shortClassName = componentClassName.substring(lastDotIndex + 1);

    // Here is euristic. Chop first 'J' letter for standard Swing classes.
    // Without 'J' bindings look better.
    //noinspection HardCodedStringLiteral
    if(
      shortClassName.length() > 1 && Character.isUpperCase(shortClassName.charAt(1)) &&
      componentClassName.startsWith("javax.swing.") &&
      StringUtil.startsWithChar(shortClassName, 'J')
    ){
      shortClassName = shortClassName.substring(1);
    }
    shortClassName = StringUtil.decapitalize(shortClassName);

    LOG.assertTrue(shortClassName.length() > 0);

    // Generate member name based on current code style
    //noinspection ForLoopThatDoesntUseLoopVariable
    for(int i = 0; true; i++){
      final String nameCandidate = shortClassName + (i + 1);
      final String binding = CodeStyleManager.getInstance(editor.getProject()).propertyNameToVariableName(
        nameCandidate,
        VariableKind.FIELD
      );

      if (!FormEditingUtil.bindingExists(editor.getRootContainer(), binding)) {
        return binding;
      }
    }
  }

  /**
   * Tries to create binding for {@link #myInsertedComponent}
   * @param editor
   * @param insertedComponent
   */
  public static void createBindingWhenDrop(final GuiEditor editor, final RadComponent insertedComponent) {
    if(isInputComponent(insertedComponent)){
      doCreateBindingWhenDrop(editor, insertedComponent);
    }
  }

  private static void doCreateBindingWhenDrop(final GuiEditor editor, final RadComponent insertedComponent) {
    // Now if the inserted component is a input control, we need to automatically create binding
    final String binding = suggestBinding(editor, insertedComponent.getComponentClassName());
    insertedComponent.setBinding(binding);

    // Try to create field in the corresponding bound class
    final String classToBind = editor.getRootContainer().getClassToBind();
    if(classToBind != null){
      final PsiClass aClass = FormEditingUtil.findClassToBind(editor.getModule(), classToBind);
      if(aClass != null){
        ApplicationManager.getApplication().runWriteAction(
          new Runnable() {
            public void run() {
              CreateFieldFix.runImpl(editor.getProject(),
                                     editor.getRootContainer(),
                                     aClass,
                                     insertedComponent.getComponentClassName(),
                                     binding,
                                     false // silently skip all errors (if any)
              );
            }
          }
        );
      }
    }
  }

  protected void processMouseEvent(final MouseEvent e){
    final int id = e.getID();
   switch (id) {
        case  MouseEvent.MOUSE_PRESSED:
          processMousePressed(e);
          break;
        case  MouseEvent.MOUSE_RELEASED:
          processMouseReleased();
          break;
        case  MouseEvent.MOUSE_DRAGGED:
          processMouseDragged(e);
          break;
    }
  }

  private void processMouseDragged(final MouseEvent e) {
    if (myTargetContainer != null && myTargetContainer.isXY()) {
      final int width = e.getX() - myInitialPoint.x;
      final int height = e.getY() - myInitialPoint.y;

      final Dimension newSize = myInsertedComponent.getSize();

      if (width >= myInitialSize.width) {
        newSize.width = width;
      }
      if (height >= myInitialSize.height) {
        newSize.height = height;
      }
      myInsertedComponent.setSize(newSize);
      myEditor.refresh();
    }
  }

  private void processMouseReleased() {
    if (!mySticky) {
      PaletteManager.getInstance(myEditor.getProject()).clearActiveItem();
    }

    if (myTargetContainer != null) {
      if (myTargetContainer.isXY()) {
        Dimension newSize = myInsertedComponent.getSize();
        if (newSize.equals(myInitialSize) && myShouldSetPreferredSizeIfNotResized) {
          // if component dropped into XY and was not resized, make it preferred size
          newSize = myInsertedComponent.getPreferredSize();
        }
        Util.adjustSize(myInsertedComponent.getDelegee(), myInsertedComponent.getConstraints(), newSize);
        myInsertedComponent.setSize(newSize);
      }

      myEditor.refreshAndSave(true);
    }
  }

  private void processMousePressed(final MouseEvent e) {
    final ComponentItem item = PaletteManager.getInstance(myEditor.getProject()).getActiveItem(ComponentItem.class);
    processComponentInsert(e.getPoint(), null, item, false);
  }

  // either point or targetContainer is null
  public void processComponentInsert(@Nullable final Point point, @Nullable final RadContainer targetContainer,
                                     final ComponentItem item, final boolean keepDefaultSize) {
    myEditor.getActiveDecorationLayer().removeFeedback();
    myEditor.setDesignTimeInsets(2);

    if (!validateNestedFormInsert(item)) {
      return;
    }

    if (!myEditor.ensureEditable()) {
      return;
    }

    myInsertedComponent = createInsertedComponent(myEditor, item);

    final GridInsertLocation location = (point != null)
      ? GridInsertProcessor.getGridInsertLocation(myEditor, point.x, point.y, 0, 1)
      : new GridInsertLocation(GridInsertMode.InCell);
    boolean dropAllowed;
    if (point != null) {
      dropAllowed = FormEditingUtil.canDrop(myEditor, point.x, point.y, 1);
    }
    else {
      assert targetContainer != null;
      dropAllowed = targetContainer.canDrop(null, 1);
    }

    if (dropAllowed || location.getMode() != GridInsertMode.InCell) {
      CommandProcessor.getInstance().executeCommand(
        myEditor.getProject(),
        new Runnable(){
          public void run(){
            createBindingWhenDrop(myEditor, myInsertedComponent);

            final RadComponent[] components = new RadComponent[]{myInsertedComponent};
            if (location.getMode() == GridInsertMode.InCell) {
              if (point != null) {
                myTargetContainer = FormEditingUtil.drop(myEditor, point.x, point.y, components, new int[]{0}, new int[]{0});
              }
              else {
                assert targetContainer != null;
                myTargetContainer = targetContainer;
                myTargetContainer.drop(null, components, null, null);
              }
            }
            else {
              myTargetContainer = myGridInsertProcessor.processGridInsertOnDrop(location, components, null);
              if (myTargetContainer == null) {
                return;
              }
            }

            FormEditingUtil.clearSelection(myEditor.getRootContainer());
            myInsertedComponent.setSelected(true);

            if (!keepDefaultSize) {
              myInitialSize = null;
              myShouldSetPreferredSizeIfNotResized = true;
              if (myTargetContainer.isXY()) {
                setCursor(Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR));
                myInitialSize = myInsertedComponent.getSize();
                if (myInitialSize.width > 0 && myInitialSize.height > 0) {
                  myShouldSetPreferredSizeIfNotResized = false;
                }
                else {
                  // size was not specified as initial value
                  myInitialSize = new Dimension(7, 7);
                }
                Util.adjustSize(myInsertedComponent.getDelegee(), myInsertedComponent.getConstraints(), myInitialSize);
                myInsertedComponent.setSize(myInitialSize);
              }
            }
            else {
              if (myTargetContainer.isXY()) {
                Dimension newSize = myInsertedComponent.getPreferredSize(); 
                Util.adjustSize(myInsertedComponent.getDelegee(), myInsertedComponent.getConstraints(), newSize);
                myInsertedComponent.setSize(newSize);
              }
            }

            if (!GuiDesignerConfiguration.getInstance(myEditor.getProject()).IRIDA_LAYOUT_MODE &&
                myInsertedComponent.getParent() instanceof RadRootContainer &&
                myInsertedComponent instanceof RadAtomicComponent) {
              GridBuildUtil.convertToGrid(myEditor);
              FormEditingUtil.clearSelection(myEditor.getRootContainer());
              myInsertedComponent.setSelected(true);
            }

            checkBindTopLevelPanel();

            myEditor.refreshAndSave(false);

            myInitialPoint = point;
          }

        },
        null,
        null
      );
    }
  }

  private boolean validateNestedFormInsert(final ComponentItem item) {
    PsiFile boundForm = item.getBoundForm();
    if (boundForm != null) {
      try {
        Utils.validateNestedFormLoop(GuiEditorUtil.buildResourceName(boundForm), new PsiNestedFormLoader(myEditor.getModule()));
      }
      catch(CodeGenerationException ex) {
        Messages.showErrorDialog(myEditor, ex.getMessage(), CommonBundle.getErrorTitle());
        return false;
      }
    }
    return true;
  }

  public static RadComponent createInsertedComponent(GuiEditor editor, ComponentItem item) {
    RadComponent result;
    final String id = editor.generateId();

    if (JScrollPane.class.getName().equals(item.getClassName())) {
      result = new RadScrollPane(editor.getModule(), id);
    }
    else if (item == Palette.getInstance(editor.getProject()).getPanelItem()) {
      result = new RadContainer(editor.getModule(), id);
    }
    else {
      if (VSpacer.class.getName().equals(item.getClassName())) {
        result = new RadVSpacer(editor.getModule(), id);
      }
      else if (HSpacer.class.getName().equals(item.getClassName())) {
        result = new RadHSpacer(editor.getModule(), id);
      }
      else if (JTabbedPane.class.getName().equals(item.getClassName())) {
        result = new RadTabbedPane(editor.getModule(), id);
      }
      else if (JSplitPane.class.getName().equals(item.getClassName())) {
        result = new RadSplitPane(editor.getModule(), id);
      }
      else if (JToolBar.class.getName().equals(item.getClassName())) {
        result = new RadToolBar(editor.getModule(), id);
      }
      else {
        PsiFile boundForm = item.getBoundForm();
        if (boundForm != null) {
          try {
            result = new RadNestedForm(editor.getModule(), GuiEditorUtil.buildResourceName(boundForm), id);
          }
          catch(Exception ex) {
            result = RadErrorComponent.create(
              editor.getModule(),
              id,
              item.getClassName(),
              null,
              ex.getMessage()
            );
          }
        }
        else {
          final ClassLoader loader = LoaderFactory.getInstance(editor.getProject()).getLoader(editor.getFile());
          try {
            final Class aClass = Class.forName(item.getClassName(), true, loader);
            result = new RadAtomicComponent(editor.getModule(), aClass, id);
          }
          catch (final Exception exc) {
            //noinspection NonConstantStringShouldBeStringBuffer
            String errorDescription = Utils.validateJComponentClass(loader, item.getClassName());
            if (errorDescription == null) {
              errorDescription = UIDesignerBundle.message("error.class.cannot.be.instantiated", item.getClassName());
              final String message = FormEditingUtil.getExceptionMessage(exc);
              if (message != null) {
                errorDescription += ": " + message;
              }
            }
            result = RadErrorComponent.create(
              editor.getModule(),
              id,
              item.getClassName(),
              null,
              errorDescription
            );
          }
        }
      }
    }
    result.init(editor, item);
    return result;
  }

  private void checkBindTopLevelPanel() {
    if (myEditor.getRootContainer().getComponentCount() == 1) {
      final RadComponent component = myEditor.getRootContainer().getComponent(0);
      if (component.getBinding() == null) {
        if (component == myInsertedComponent ||
            (component instanceof RadContainer && ((RadContainer) component).getComponentCount() == 1 &&
             component == myInsertedComponent.getParent())) {
          doCreateBindingWhenDrop(myEditor, component);
        }
      }
    }
  }

  protected boolean cancelOperation() {
    myEditor.setDesignTimeInsets(2);
    myEditor.getActiveDecorationLayer().removeFeedback();
    return false;
  }

  public Cursor processMouseMoveEvent(final MouseEvent e) {
    return myGridInsertProcessor.processMouseMoveEvent(e.getX(), e.getY(), false, 1, 0);
  }
}
