document.addEventListener('DOMContentLoaded', function() {
    // Get DOM elements with null checks
    const dropZone = document.getElementById('drop-zone');
    const fileInput = document.getElementById('file-input');
    const uploadButton = document.getElementById('upload-button');
    const progressBar = document.getElementById('progress-bar');
    const progressText = document.getElementById('progress-text');

    // Check if required elements exist
    if (!dropZone || !fileInput || !uploadButton || !progressBar || !progressText) {
        console.error('Required DOM elements not found:', {
            dropZone: !!dropZone,
            fileInput: !!fileInput,
            uploadButton: !!uploadButton,
            progressBar: !!progressBar,
            progressText: !!progressText
        });
        return;
    }

    console.log('DOM elements loaded successfully');
    
    // Add drag and drop event listeners
    dropZone.addEventListener('dragover', (e) => {
        e.preventDefault();
        dropZone.classList.add('dragover');
    });

    dropZone.addEventListener('dragleave', () => {
        dropZone.classList.remove('dragover');
    });

    dropZone.addEventListener('drop', (e) => {
        e.preventDefault();
        dropZone.classList.remove('dragover');
        const files = e.dataTransfer.files;
        handleFiles(files);
    });

    // Click to upload
    dropZone.addEventListener('click', () => {
        fileInput.click();
    });

    fileInput.addEventListener('change', (e) => {
        handleFiles(e.target.files);
    });

    function handleFiles(files) {
        if (files.length > 0) {
            const file = files[0];
            if (file.name.endsWith('.jar')) {
                console.log(`File selected: ${file.name} (${formatFileSize(file.size)})`);
                uploadButton.style.display = 'block';
                uploadButton.textContent = `Upload ${file.name}`;
            } else {
                console.error('Invalid file type selected. Please select a .jar file');
                alert('Please select a .jar file');
            }
        }
    }

    uploadButton.addEventListener('click', async () => {
        const file = fileInput.files[0];
        const selectedVersion = document.getElementById("mc-version").value;
        
        if (!file) {
            console.error('No file selected');
            alert('Please select a file first');
            return;
        }

        try {
            // Create new filename based on timestamp
            const timestamp = new Date().getTime();
            const newFileName = `mod${timestamp}.jar`;
            
            // Create new File object with the new name
            const renamedFile = new File([file], newFileName, {
                type: file.type,
                lastModified: file.lastModified,
            });

            console.log(`Original filename: ${file.name}`);
            console.log(`New filename: ${newFileName}`);

            const formData = new FormData();
            formData.append('file', renamedFile);
            formData.append('version', selectedVersion);
            formData.append('originalName', file.name); // Store original name if needed

            // Show progress
            progressBar.style.display = 'block';
            progressBar.value = 0;
            progressText.textContent = 'Uploading...';
            uploadButton.disabled = true;

            const response = await fetch('https://modupdater.onrender.com/api/upload', {
                method: 'POST',
                body: formData
            });

            if (!response.ok) {
                throw new Error(`Upload failed: ${response.statusText}`);
            }

            console.log('File upload successful');
            progressBar.value = 50;
            progressText.textContent = 'Processing...';

            console.log('Starting conversion process...');
            const convertResponse = await fetch("https://modupdater.onrender.com/api/convert", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ version: selectedVersion })
            });

            if (!convertResponse.ok) {
                throw new Error(`Conversion failed: ${convertResponse.statusText}`);
            }

            const result = await convertResponse.json();
            console.log("Conversion successful!", result);
            console.log("Changes found:", {
                added: result.added?.length || 0,
                removed: result.removed?.length || 0,
                modified: result.modified?.length || 0
            });

            progressBar.value = 100;
            progressText.textContent = 'Complete!';
            
            const resultDiv = document.getElementById("result");
            if (resultDiv) {
                resultDiv.innerHTML = `
                    <h3>Conversion Complete!</h3>
                    <pre>${JSON.stringify(result, null, 2)}</pre>
                `;
            }

        } catch (error) {
            console.error('Error during process:', error);
            console.error('Error details:', {
                message: error.message,
                type: error.name,
                stack: error.stack
            });
            progressText.textContent = `Error: ${error.message}`;
            progressBar.classList.add('error');
        } finally {
            console.log('Process completed');
            uploadButton.disabled = false;
        }
    });

    // Utility function to format file size
    function formatFileSize(bytes) {
        if (bytes === 0) return '0 Bytes';
        const k = 1024;
        const sizes = ['Bytes', 'KB', 'MB', 'GB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
    }
});