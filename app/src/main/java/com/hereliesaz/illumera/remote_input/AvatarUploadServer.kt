package com.hereliesaz.illumera.remote_input

import android.os.Handler
import android.os.Looper
import android.util.Base64
import fi.iki.elonen.NanoHTTPD
import java.util.UUID

/**
 * A lightweight HTTP server that serves a mobile-friendly image upload page
 * with circle cropping capability. Receives the cropped image as Base64 data.
 */
class AvatarUploadServer(
    port: Int,
    private val pairingToken: String,
    private val onImageReceived: (ByteArray) -> Unit
) : NanoHTTPD(port) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val csrfToken = UUID.randomUUID().toString()

    companion object {
        private const val MAX_IMAGE_SIZE = 5 * 1024 * 1024 // 5 MB
    }

    override fun serve(session: IHTTPSession): Response {
        if (session.uri == "/ping") return DisconnectBanner.pingResponse()
        // Require the pairing token from the QR/link URL on every request — proves
        // the caller actually saw the URL shown on this TV.
        if (session.parms["pin"] != pairingToken) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
        return when (session.method) {
            Method.GET -> serveUploadForm()
            Method.POST -> handleImageUpload(session)
            else -> newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Method not allowed")
        }
    }

    private fun serveUploadForm(): Response {
        val html = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                <title>Upload Avatar</title>
                <style>
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                        background-color: #121212;
                        color: #ffffff;
                        min-height: 100vh;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        padding: 20px;
                        overflow: hidden;
                    }
                    .container {
                        background-color: #1e1e1e;
                        border-radius: 16px;
                        padding: 24px;
                        width: 100%;
                        max-width: 400px;
                        box-shadow: 0 4px 6px rgba(0,0,0,0.3);
                        text-align: center;
                    }
                    h1 {
                        color: #fff;
                        font-size: 1.5rem;
                        font-weight: 600;
                        margin-bottom: 0.5rem;
                        text-align: center;
                    }
                    p {
                        color: #aaaaaa;
                        font-size: 14px;
                        text-align: center;
                        margin-bottom: 20px;
                    }
                    .upload-area {
                        border: 2px dashed #333;
                        border-radius: 12px;
                        padding: 30px;
                        text-align: center;
                        cursor: pointer;
                        transition: border-color 0.2s, background 0.2s;
                        margin-bottom: 16px;
                    }
                    .upload-area:hover, .upload-area.dragover {
                        border-color: #555;
                        background: rgba(255, 255, 255, 0.05);
                    }
                    #fileInput {
                        display: none;
                    }

                    /* Cropper Container */
                    .cropper-container {
                        display: none;
                        flex-direction: column;
                        align-items: center;
                    }
                    .crop-area {
                        position: relative;
                        width: 280px;
                        height: 280px;
                        overflow: hidden;
                        border-radius: 50%;
                        background: #000;
                        margin-bottom: 16px;
                        touch-action: none;
                    }
                    .crop-image {
                        position: absolute;
                        cursor: move;
                        user-select: none;
                        -webkit-user-drag: none;
                    }
                    .crop-overlay {
                        position: absolute;
                        top: 0;
                        left: 0;
                        right: 0;
                        bottom: 0;
                        border: 3px solid rgba(255, 255, 255, 0.8);
                        border-radius: 50%;
                        pointer-events: none;
                        box-shadow: 0 0 0 9999px rgba(0, 0, 0, 0.5);
                    }
                    .zoom-slider {
                        width: 100%;
                        max-width: 250px;
                        margin: 16px 0;
                        -webkit-appearance: none;
                        background: #333;
                        height: 6px;
                        border-radius: 3px;
                    }
                    .zoom-slider::-webkit-slider-thumb {
                        -webkit-appearance: none;
                        width: 24px;
                        height: 14px;
                        background: #ffffff;
                        border-radius: 7px;
                        cursor: pointer;
                    }
                    .zoom-label {
                        color: #aaaaaa;
                        font-size: 12px;
                        margin-bottom: 4px;
                    }

                    button {
                        width: 100%;
                        padding: 14px 24px;
                        font-size: 1rem;
                        font-weight: 600;
                        border: none;
                        border-radius: 24px;
                        background-color: #ffffff;
                        color: #000000;
                        cursor: pointer;
                        transition: transform 0.1s, opacity 0.2s;
                        margin-top: 8px;
                    }
                    button:active {
                        transform: scale(0.98);
                    }
                    button:disabled {
                        opacity: 0.6;
                        cursor: not-allowed;
                    }
                    .success {
                        text-align: center;
                        color: #10b981;
                        font-size: 18px;
                        padding: 40px 0;
                    }
                    .success svg {
                        width: 64px;
                        height: 64px;
                        margin-bottom: 16px;
                    }
                </style>
            </head>
            <body>
                <div class="container">
                    <!-- Upload Step -->
                    <div id="upload-step">
                        <h1>📷 Upload Avatar</h1>
                        <p>Choose a picture for your profile</p>
                        <div class="upload-area" id="uploadArea">
                            <div style="font-size:2rem;margin-bottom:1rem;">🖼️</div>
                            <div>Tap to select image</div>
                        </div>
                        <input type="file" id="fileInput" accept="image/*">
                    </div>
                    
                    <!-- Crop Step -->
                    <div class="cropper-container" id="crop-step">
                        <h1>✂️ Adjust Your Photo</h1>
                        <p>Drag to move, pinch or use slider to zoom</p>
                        <div class="crop-area" id="cropArea">
                            <img class="crop-image" id="cropImage" src="">
                            <div class="crop-overlay"></div>
                        </div>
                        <span class="zoom-label">Zoom</span>
                        <input type="range" class="zoom-slider" id="zoomSlider" min="50" max="300" step="1" value="150">
                        <button id="doneBtn">Done</button>
                    </div>
                    
                    <!-- Success Step -->
                    <div class="success" id="success-step" style="display: none;">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                            <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/>
                            <polyline points="22 4 12 14.01 9 11.01"/>
                        </svg>
                        <div>Avatar uploaded!</div>
                        <p style="margin-top: 12px;">You can close this page now.</p>
                    </div>
                </div>
                
                <script>
                    const uploadStep = document.getElementById('upload-step');
                    const cropStep = document.getElementById('crop-step');
                    const successStep = document.getElementById('success-step');
                    const uploadArea = document.getElementById('uploadArea');
                    const fileInput = document.getElementById('fileInput');
                    const cropArea = document.getElementById('cropArea');
                    const cropImage = document.getElementById('cropImage');
                    const zoomSlider = document.getElementById('zoomSlider');
                    const doneBtn = document.getElementById('doneBtn');
                    
                    let scale = 1;
                    let minScale = 0.5;
                    let maxScale = 3;
                    let posX = 0, posY = 0;
                    let startX, startY, startPosX, startPosY;
                    let isDragging = false;
                    let initialPinchDistance = null;
                    let initialScale = 1;
                    
                    // Upload area click
                    uploadArea.addEventListener('click', () => fileInput.click());
                    
                    // Drag and drop
                    uploadArea.addEventListener('dragover', (e) => {
                        e.preventDefault();
                        uploadArea.classList.add('dragover');
                    });
                    uploadArea.addEventListener('dragleave', () => {
                        uploadArea.classList.remove('dragover');
                    });
                    uploadArea.addEventListener('drop', (e) => {
                        e.preventDefault();
                        uploadArea.classList.remove('dragover');
                        const file = e.dataTransfer.files[0];
                        if (file && file.type.startsWith('image/')) {
                            loadImage(file);
                        }
                    });
                    
                    // File input change
                    fileInput.addEventListener('change', (e) => {
                        const file = e.target.files[0];
                        if (file) loadImage(file);
                    });
                    
                    function loadImage(file) {
                        const reader = new FileReader();
                        reader.onload = (e) => {
                            cropImage.onload = () => {
                                // Calculate cover-fit scale (image covers entire crop area)
                                const areaSize = 280;
                                const scaleW = areaSize / cropImage.naturalWidth;
                                const scaleH = areaSize / cropImage.naturalHeight;
                                const coverScale = Math.max(scaleW, scaleH);

                                // Set dynamic zoom range: half cover to 3x cover
                                minScale = coverScale * 0.5;
                                maxScale = coverScale * 3;
                                scale = coverScale;

                                // Update slider to put cover-fit in the middle
                                zoomSlider.min = minScale * 100;
                                zoomSlider.max = maxScale * 100;
                                zoomSlider.value = scale * 100;

                                // Center the image
                                centerImage();

                                // Show crop step
                                uploadStep.style.display = 'none';
                                cropStep.style.display = 'flex';
                            };
                            cropImage.src = e.target.result;
                        };
                        reader.readAsDataURL(file);
                    }

                    function centerImage() {
                        const areaSize = 280;
                        const imgW = cropImage.naturalWidth * scale;
                        const imgH = cropImage.naturalHeight * scale;
                        posX = (areaSize - imgW) / 2;
                        posY = (areaSize - imgH) / 2;
                        updateImageTransform();
                    }
                    
                    function updateImageTransform() {
                        cropImage.style.width = (cropImage.naturalWidth * scale) + 'px';
                        cropImage.style.height = (cropImage.naturalHeight * scale) + 'px';
                        cropImage.style.left = posX + 'px';
                        cropImage.style.top = posY + 'px';
                    }
                    
                    // Mouse drag
                    cropImage.addEventListener('mousedown', (e) => {
                        e.preventDefault();
                        isDragging = true;
                        startX = e.clientX;
                        startY = e.clientY;
                        startPosX = posX;
                        startPosY = posY;
                    });
                    
                    document.addEventListener('mousemove', (e) => {
                        if (!isDragging) return;
                        posX = startPosX + (e.clientX - startX);
                        posY = startPosY + (e.clientY - startY);
                        updateImageTransform();
                    });
                    
                    document.addEventListener('mouseup', () => {
                        isDragging = false;
                    });
                    
                    // Touch drag
                    cropImage.addEventListener('touchstart', (e) => {
                        if (e.touches.length === 1) {
                            isDragging = true;
                            startX = e.touches[0].clientX;
                            startY = e.touches[0].clientY;
                            startPosX = posX;
                            startPosY = posY;
                        } else if (e.touches.length === 2) {
                            isDragging = false;
                            initialPinchDistance = getPinchDistance(e.touches);
                            initialScale = scale;
                            startPosX = posX;
                            startPosY = posY;
                        }
                    });

                    cropImage.addEventListener('touchmove', (e) => {
                        e.preventDefault();
                        if (e.touches.length === 1 && isDragging) {
                            posX = startPosX + (e.touches[0].clientX - startX);
                            posY = startPosY + (e.touches[0].clientY - startY);
                            updateImageTransform();
                        } else if (e.touches.length === 2) {
                            const currentDistance = getPinchDistance(e.touches);
                            const zoomChange = currentDistance / initialPinchDistance;
                            const newScale = Math.min(Math.max(initialScale * zoomChange, minScale), maxScale);
                            const areaSize = 280;
                            const cx = areaSize / 2;
                            const cy = areaSize / 2;
                            posX = cx - (cx - startPosX) * (newScale / initialScale);
                            posY = cy - (cy - startPosY) * (newScale / initialScale);
                            scale = newScale;
                            zoomSlider.value = scale * 100;
                            updateImageTransform();
                        }
                    });
                    
                    cropImage.addEventListener('touchend', () => {
                        isDragging = false;
                        initialPinchDistance = null;
                    });
                    
                    function getPinchDistance(touches) {
                        return Math.hypot(
                            touches[1].clientX - touches[0].clientX,
                            touches[1].clientY - touches[0].clientY
                        );
                    }
                    
                    // Zoom slider
                    zoomSlider.addEventListener('input', (e) => {
                        const oldScale = scale;
                        scale = e.target.value / 100;
                        
                        // Zoom towards center
                        const areaSize = 280;
                        const centerX = areaSize / 2;
                        const centerY = areaSize / 2;
                        
                        posX = centerX - (centerX - posX) * (scale / oldScale);
                        posY = centerY - (centerY - posY) * (scale / oldScale);
                        
                        updateImageTransform();
                    });
                    
                    // Done button
                    doneBtn.addEventListener('click', async () => {
                        doneBtn.disabled = true;
                        doneBtn.textContent = 'Uploading...';
                        
                        try {
                            // Create canvas and crop
                            const canvas = document.createElement('canvas');
                            const size = 512; // Output size
                            canvas.width = size;
                            canvas.height = size;
                            const ctx = canvas.getContext('2d');
                            
                            // Scale factor for canvas
                            const canvasScale = size / 280;
                            
                            // Draw cropped image
                            ctx.beginPath();
                            ctx.arc(size / 2, size / 2, size / 2, 0, Math.PI * 2);
                            ctx.closePath();
                            ctx.clip();
                            
                            ctx.drawImage(
                                cropImage,
                                posX * canvasScale,
                                posY * canvasScale,
                                cropImage.naturalWidth * scale * canvasScale,
                                cropImage.naturalHeight * scale * canvasScale
                            );
                            
                            // Get base64 data
                            const dataUrl = canvas.toDataURL('image/png');
                            const base64Data = dataUrl.split(',')[1];
                            
                            // Send to server
                            const formData = new FormData();
                            formData.append('image', base64Data);
                            formData.append('csrf_token', '${csrfToken}');

                            await fetch('/' + window.location.search, { method: 'POST', body: formData });
                            
                            cropStep.style.display = 'none';
                            successStep.style.display = 'block';
                        } catch (err) {
                            doneBtn.disabled = false;
                            doneBtn.textContent = 'Done';
                            alert('Failed to upload. Please try again.');
                        }
                    });
                </script>
                ${DisconnectBanner.htmlSnippet}
            </body>
            </html>
        """.trimIndent()

        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    private fun handleImageUpload(session: IHTTPSession): Response {
        try {
            val files = mutableMapOf<String, String>()
            session.parseBody(files)

            val token = session.parms["csrf_token"]
            if (token != csrfToken) {
                return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Invalid request")
            }

            val base64Image = session.parms["image"]

            if (!base64Image.isNullOrBlank()) {
                // Reject on the encoded length before the (much larger) decode allocation —
                // base64 is ~4/3 the size of the decoded bytes, so this bounds the second
                // full-size copy the request would otherwise force onto the heap.
                if (base64Image.length > MAX_IMAGE_SIZE * 2) {
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Image too large (max 5 MB)")
                }
                val imageBytes = Base64.decode(base64Image, Base64.DEFAULT)

                // Reject oversized uploads
                if (imageBytes.size > MAX_IMAGE_SIZE) {
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Image too large (max 5 MB)")
                }

                // Validate image magic bytes (PNG or JPEG)
                if (!isValidImage(imageBytes)) {
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid image format")
                }

                mainHandler.post {
                    onImageReceived(imageBytes)
                }
            }

            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")
        } catch (e: Exception) {
            com.hereliesaz.illumera.crash.AppErrors.w("AvatarUploadServer", "Error handling upload", e)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error processing request")
        }
    }

    private fun isValidImage(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        // PNG: 89 50 4E 47
        if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) return true
        // JPEG: FF D8 FF
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) return true
        // WebP: RIFF....WEBP
        if (bytes.size >= 12 &&
            bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&  // RI
            bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&  // FF
            bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() &&  // WE
            bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()   // BP
        ) return true
        return false
    }
}
