/*
 * Copyright 2018 SPF4J.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.spf4j.ui;
//CHECKSTYLE:OFF
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URL;
import java.net.URLConnection;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import javax.swing.JComponent;
import javax.swing.TransferHandler;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;
import javax.swing.text.Position;
import org.apache.avro.Schema;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.Decoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.spf4j.base.avro.LogRecord;
import org.spf4j.base.avro.StackSampleElement;
import org.spf4j.ssdump2.Converter;
import org.spf4j.stackmonitor.SampleNode;

/**
 *
 * @author Zoltan Farkas
 */
@SuppressFBWarnings({"SE_TRANSIENT_FIELD_NOT_RESTORED", "SE_BAD_FIELD"})
public class TextEntryPanel extends javax.swing.JPanel {

  private transient BiConsumer<String, SampleNode> nodeConsumer;

  private transient Consumer<Exception> errorConsumer;

  /**
   * Creates new form TextEntryPanel
   */
  public TextEntryPanel(final BiConsumer<String, SampleNode> nodeConsumer,
          final Consumer<Exception> errorConsumer) {
    initComponents();
    this.nodeConsumer = nodeConsumer;
    this.errorConsumer = errorConsumer;
    TextTransferHandler th = new TextTransferHandler();
    jTextPane1.setTransferHandler(th);
  }

  /**
   * This method is called from within the constructor to initialize the form. WARNING: Do NOT modify this code. The
   * content of this method is always regenerated by the Form Editor.
   */
  @SuppressWarnings("unchecked")
  // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
  private void initComponents() {

    jScrollPane1 = new javax.swing.JScrollPane();
    jTextPane1 = new javax.swing.JTextPane();
    javax.swing.JButton display = new javax.swing.JButton();

    jTextPane1.setText("Enter stack sample json represetation or url to retrieve Log Records containing stack samples ( https://demo.spf4j.org/logs/cluster?filter=log.stackSamples.length!=0 )");
    jTextPane1.setName("textBox"); // NOI18N
    jTextPane1.setOpaque(false);
    jTextPane1.setRequestFocusEnabled(false);
    jScrollPane1.setViewportView(jTextPane1);

    display.setText("Display");
    display.setName("display"); // NOI18N
    display.addActionListener(new java.awt.event.ActionListener() {
      public void actionPerformed(java.awt.event.ActionEvent evt) {
        displayActionPerformed(evt);
      }
    });

    javax.swing.GroupLayout layout = new javax.swing.GroupLayout(this);
    this.setLayout(layout);
    layout.setHorizontalGroup(
      layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
      .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, layout.createSequentialGroup()
        .addComponent(jScrollPane1, javax.swing.GroupLayout.DEFAULT_SIZE, 409, Short.MAX_VALUE)
        .addContainerGap())
      .addGroup(layout.createSequentialGroup()
        .addGap(152, 152, 152)
        .addComponent(display)
        .addContainerGap(186, Short.MAX_VALUE))
    );
    layout.setVerticalGroup(
      layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
      .addGroup(layout.createSequentialGroup()
        .addContainerGap()
        .addComponent(jScrollPane1, javax.swing.GroupLayout.PREFERRED_SIZE, 256, javax.swing.GroupLayout.PREFERRED_SIZE)
        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
        .addComponent(display)
        .addContainerGap())
    );
  }// </editor-fold>//GEN-END:initComponents

  public static Object readAvroBin(final InputStream input, final Schema writerSchema)
          throws IOException {
    DatumReader reader = new SpecificDatumReader(writerSchema);
    DecoderFactory decoderFactory = DecoderFactory.get();
    Decoder decoder = decoderFactory.binaryDecoder(input, null);
    return reader.read(null, decoder);
  }


  @SuppressFBWarnings({"UP_UNUSED_PARAMETER", "URLCONNECTION_SSRF_FD"})
  private void displayActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_displayActionPerformed
    try {
      String text = jTextPane1.getText().trim();
      if (text.startsWith("http")) {
        URL url = new URL(text);
        URLConnection conn = url.openConnection();
        conn.setRequestProperty("Accept", "application/avro");
        conn.connect();
        String contentType = conn.getContentType();
        if (!"application/avro".equals(contentType)) {
          throw new IOException("Unsupported content type " + contentType);
        }
        try (InputStream is = new BufferedInputStream(conn.getInputStream())) {
          List<LogRecord> recs =
                  (List<LogRecord>) readAvroBin(is, Schema.createArray(LogRecord.SCHEMA$));
          for (LogRecord rec : recs) {
            List<StackSampleElement> stackSamples = rec.getStackSamples();
            if (!stackSamples.isEmpty()) {
              nodeConsumer.accept(rec.getMsg() + "; with trId=" + rec.getTrId(),
                      Converter.convert(stackSamples.iterator()));
            }
          }
        }
      } else if (text.startsWith("[")) {
        Schema schema = Schema.createArray(StackSampleElement.getClassSchema());
        DatumReader reader = new SpecificDatumReader(schema);
        List<StackSampleElement> samples;
        try {
          Decoder decoder = DecoderFactory.get().jsonDecoder(schema, text);
          samples = (List<StackSampleElement>) reader.read(null, decoder);
        } catch (IOException | RuntimeException ex) {
          throw new RuntimeException("Unable to read samples: " + text, ex);
        }
        nodeConsumer.accept("SampleNode Array", Converter.convert(samples.iterator()));
      } else {
        nodeConsumer.accept("SampleNode Tree", SampleNode.parse(new StringReader(text)).getSecond());
      }
    } catch (IOException | RuntimeException ex) {
      errorConsumer.accept(ex);
    }
  }//GEN-LAST:event_displayActionPerformed


  // Variables declaration - do not modify//GEN-BEGIN:variables
  private javax.swing.JScrollPane jScrollPane1;
  private javax.swing.JTextPane jTextPane1;
  // End of variables declaration//GEN-END:variables

  private static final class TextTransferHandler extends TransferHandler {
    // Start and end position in the source text.
    // We need this information when performing a MOVE
    // in order to remove the dragged text from the source.

    Position p0 = null, p1 = null;

    /**
     * Perform the actual import. This method supports both drag and drop and cut/copy/paste.
     */
    public boolean importData(TransferHandler.TransferSupport support) {
      // If we can't handle the import, bail now.
      if (!canImport(support)) {
        return false;
      }

      // Fetch the data -- bail if this fails
      String data;
      try {
        data = (String) support.getTransferable().getTransferData(
                DataFlavor.stringFlavor);
      } catch (UnsupportedFlavorException | IOException e) {
        Logger.getLogger(TextEntryPanel.class.getName()).log(Level.WARNING, "Exception encountered", e);
        return false;
      }

      javax.swing.JTextPane tc = (javax.swing.JTextPane) support.getComponent();
      tc.replaceSelection(data);
      return true;
    }

    /**
     * Bundle up the data for export.
     */
    @Nullable
    protected Transferable createTransferable(JComponent c) {
      javax.swing.JTextPane source = (javax.swing.JTextPane) c;
      int start = source.getSelectionStart();
      int end = source.getSelectionEnd();
      Document doc = source.getDocument();
      if (start == end) {
        return null;
      }
      try {
        p0 = doc.createPosition(start);
        p1 = doc.createPosition(end);
      } catch (BadLocationException e) {
        throw new RuntimeException(e);
      }
      String data = source.getSelectedText();
      return new StringSelection(data);
    }

    /**
     * These text fields handle both copy and move actions.
     */
    public int getSourceActions(JComponent c) {
      return COPY_OR_MOVE;
    }

    /**
     * When the export is complete, remove the old text if the action was a move.
     */
    protected void exportDone(JComponent c, Transferable data, int action) {
      if (action != MOVE) {
        return;
      }

      if ((p0 != null) && (p1 != null) && (p0.getOffset() != p1.getOffset())) {
        try {
          JTextComponent tc = (JTextComponent) c;
          tc.getDocument()
                  .remove(p0.getOffset(), p1.getOffset() - p0.getOffset());
        } catch (BadLocationException e) {
          throw new RuntimeException(e);
        }
      }
    }

    /**
     * We only support importing strings.
     */
    public boolean canImport(TransferHandler.TransferSupport support) {
      // we only import Strings
      if (!support.isDataFlavorSupported(DataFlavor.stringFlavor)) {
        return false;
      }
      return true;
    }
  }

}
