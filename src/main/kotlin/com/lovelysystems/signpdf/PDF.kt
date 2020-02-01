import com.lovelysystems.signpdf.signer.Signer
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.PDResources
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.common.PDStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField
import org.apache.pdfbox.util.Matrix
import java.awt.Color
import java.awt.geom.AffineTransform
import java.io.*
import java.util.*

class PDF(inputStream: InputStream, outputStream: OutputStream, signName: String = "", signReason: String = "", signLocation: String = "", signContact: String = "", signIP: String = "" , backgroundImagePath: String = "", signatureImage: InputStream? , certificationLevel: Int = 1) {

    private var document = PDDocument.load(inputStream)

    private var signatureImage = signatureImage

    private val certificationLevel = certificationLevel

    private val outputStream = outputStream

    private val signName = signName

    private val signReason = signReason

    private val signLocation = signLocation

    private val signContact = signContact

    private val signIP = signIP

    private val signDate = Calendar.getInstance()

    private val backgroundImageFile = File(backgroundImagePath)

    fun sign(signer: Signer) {
        val signature = PDSignature()
        signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE)
        signature.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED)
        signature.name = signName
        signature.location = signLocation
        signature.reason = signReason
        signature.contactInfo = signContact
        signature.signDate = signDate
        var rect: PDRectangle? = null
        var tos = ByteArrayOutputStream()

        println("Page Count:"+document.pages.count)
        // Add signature page
        var signaturePage = PDPage(PDRectangle.LETTER)
        document.addPage(signaturePage)

        // Save and reload PDF to include new last page
        document.save(tos)
        document.close()
        document = PDDocument.load(tos.toByteArray())
        println("Added page")
        val signatureOptions = SignatureOptions()
        println("Page Count:"+document.pages.count)
        rect = PDRectangle(0f,0f, signaturePage.cropBox.width, signaturePage.cropBox.height)

        // Size can vary, but should be enough for purpose.
        signatureOptions.preferredSignatureSize = SignatureOptions.DEFAULT_SIGNATURE_SIZE * 2
        signatureOptions.setVisualSignature(createVisualSignatureTemplate(document, document.pages.count -1 , rect))
        signatureOptions.page = document.pages.count - 1

        // register signature dictionary and sign options
        document.addSignature(signature, signatureOptions)
        println("After Signing Page Count:"+document.pages.count)

        // create Stream for external signing
        val externalSigningSupport = document.saveIncrementalForExternalSigning(outputStream)
        val stream = externalSigningSupport.content

        val externalSignature = signer.sign(stream)

        externalSigningSupport.setSignature(externalSignature)
    }

    fun close() {
        document.close()
    }


    // create a template PDF document with empty signature and return it as a stream.
    private fun createVisualSignatureTemplate(srcDoc: PDDocument, pageNum: Int, rect: PDRectangle?): InputStream {
        PDDocument().use { doc ->
            val page = PDPage(srcDoc.getPage(pageNum).mediaBox)
            doc.addPage(page)
            val acroForm = PDAcroForm(doc)
            doc.documentCatalog.acroForm = acroForm
            val signatureField = PDSignatureField(acroForm)
            val widget = signatureField.widgets[0]
            val acroFormFields = acroForm.fields
            acroForm.isSignaturesExist = true
            acroForm.isAppendOnly = true
            acroForm.cosObject.isDirect = true
            acroFormFields.add(signatureField)
            widget.rectangle = rect!!

            // from PDVisualSigBuilder.createHolderForm()
            val stream = PDStream(doc)
            val form = PDFormXObject(stream)
            val res = PDResources()
            form.resources = res
            form.formType = 1
            val bbox = PDRectangle(rect.width, rect.height)
            var height = bbox.height
            var width = bbox.width
            var initialScale: Matrix? = null
            when (srcDoc.getPage(pageNum).rotation) {
                90 -> {
                    form.setMatrix(AffineTransform.getQuadrantRotateInstance(1))
                    initialScale = Matrix.getScaleInstance(bbox.width / bbox.height, bbox.height / bbox.width)
                    height = bbox.width
                    width = bbox.height
                }
                180 -> form.setMatrix(AffineTransform.getQuadrantRotateInstance(2))
                270 -> {
                    form.setMatrix(AffineTransform.getQuadrantRotateInstance(3))
                    initialScale = Matrix.getScaleInstance(bbox.width / bbox.height, bbox.height / bbox.width)
                    height = bbox.width
                    width = bbox.height
                }
                0 -> {
                }
                else -> {
                }
            }
            form.bBox = bbox
            val font = PDType1Font.HELVETICA_BOLD

            // from PDVisualSigBuilder.createAppearanceDictionary()
            val appearance = PDAppearanceDictionary()
            appearance.cosObject.isDirect = true
            val appearanceStream = PDAppearanceStream(form.cosObject)
            appearance.setNormalAppearance(appearanceStream)
            widget.appearance = appearance

            PDPageContentStream(doc, appearanceStream).use { cs ->
                // for 90Â° and 270Â° scale ratio of width / height
                // not really sure about this
                // why does scale have no effect when done in the form matrix???
                if (initialScale != null) {
                    cs.transform(initialScale)
                }

                // show background (just for debugging, to see the rect size + position)
                //cs.setNonStrokingColor(Color.yellow)
                //cs.addRect(-5000f, -5000f, 10000f, 10000f)
                //cs.fill()

                // show background image
                // save and restore graphics if the image is too large and needs to be scaled
                cs.saveGraphicsState()
                //cs.transform(Matrix.getScaleInstance(0.25f, 0.25f))
                val img = PDImageXObject.createFromFileByExtension(backgroundImageFile!!, doc)

                cs.drawImage(img, 0f, 0f,rect.width,rect.height)

                var gsig = PDImageXObject.createFromByteArray(doc,signatureImage?.readBytes(),"signature.png")

                cs.drawImage(gsig, width/2-75, height-450f,150f,100f)

                var alpha = 0.5f
                var graphicsState = PDExtendedGraphicsState()
                graphicsState.strokingAlphaConstant = alpha
                graphicsState.nonStrokingAlphaConstant = alpha
                cs.setGraphicsStateParameters(graphicsState)
                cs.setNonStrokingColor(Color.white)
                cs.addRect(100f, height-500f, width-200f, 400f)
                cs.fill()

                cs.restoreGraphicsState()




                // show text
                val fontSize = 10f
                val leading = fontSize * 1.5f
                val leadingY = fontSize * 15f
                var leadingX = fontSize * 15f
                cs.beginText()
                cs.setFont(font, fontSize)
                cs.setNonStrokingColor(Color.black)
                cs.newLineAtOffset(leadingX, height - leadingY)
                cs.setLeading(leading)
                cs.showText("Signed by : $signName")
                cs.newLine()
                cs.showText("Contact : $signContact")
                cs.newLine()
                cs.showText("Location : $signLocation")
                cs.newLine()
                cs.showText("IP Address : $signIP")
                cs.newLine()
                cs.showText("Reason : $signReason")
                cs.newLine()
                cs.showText("Signed On : " + signDate.time)
                cs.endText()


                // Draw some contours
                cs.moveTo(width/2-75, height-450f)
                cs.lineTo(width/2-75, height-350f)
                cs.stroke()
                cs.moveTo(width/2-75, height-350f)
                cs.lineTo(width/2+75, height-350f)
                cs.stroke()
                cs.moveTo(width/2+75, height-350f)
                cs.lineTo(width/2+75,height-450f)
                cs.stroke()
                cs.moveTo(width/2+75,height-450f)
                cs.lineTo(width/2-75, height-450f)
                cs.stroke()


            }

            // no need to set annotations and /P entry

            val baos = ByteArrayOutputStream()
            doc.save(baos)
            return ByteArrayInputStream(baos.toByteArray())
        }
    }
}
