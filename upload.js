// Global downloadDiff function
async function downloadDiff() {
    try {
        console.log('Initiating file download...');  // Debug log
        const response = await fetch('https://modupdater.onrender.com/api/logs/download-diff', {
            method: 'GET',
            headers: {
                'Accept': '*/*'
            },
            mode: 'cors'
        });
        
        if (!response.ok) {
            console.error('Download failed with status:', response.status);
            throw new Error(`Download failed: ${response.status}`);
        }
        
        const blob = await response.blob();
        console.log('Received blob:', blob.size, 'bytes');
        
        const filename = response.headers.get('content-disposition')
            ?.split('filename=')[1]?.replace(/"/g, '') || 'diff_report.txt';
            
        console.log('Downloading with filename:', filename);
        
        // Create download link
        const url = window.URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.style.display = 'none';
        a.href = url;
        a.download = filename;
        
        // Trigger download
        document.body.appendChild(a);
        a.click();
        
        // Cleanup with longer timeout
        setTimeout(() => {
            window.URL.revokeObjectURL(url);
            document.body.removeChild(a);
        }, 1000);
        
        console.log('Download initiated successfully');
    } catch (error) {
        console.error('Error during download:', error);
        if (error.name === 'TypeError') {
            console.error('Network error details:', error);
            alert('Network error occurred. Please try again in a few moments.');
        } else {
            alert('Failed to download diff file. Please try again later.');
        }
    }
}

// Add event listener when DOM is loaded
document.addEventListener('DOMContentLoaded', function() {
    console.log('Setting up download button listener');
    const downloadButton = document.getElementById('download-file');
    if (downloadButton) {
        downloadButton.addEventListener('click', downloadDiff);
        console.log('Download button listener attached');
    } else {
        console.error('Download button not found in DOM');
    }
});

// Add event listener to the button when DOM is loaded
document.addEventListener('DOMContentLoaded', () => {
    const dropZone = document.getElementById('drop-zone');
    const fileInput = document.getElementById('file-input');
    const uploadButton = document.getElementById('upload-button');
    const progressBar = document.getElementById('progress-bar');
    const progressText = document.getElementById('progress-text');
    const versionSelect = document.getElementById('mc-version');

    // Check if required elements exist
    if (!dropZone || !fileInput || !uploadButton || !progressBar || !progressText || !versionSelect) {
        console.error('Required DOM elements not found:', {
            dropZone: !!dropZone,
            fileInput: !!fileInput,
            uploadButton: !!uploadButton,
            progressBar: !!progressBar,
            progressText: !!progressText,
            versionSelect: !!versionSelect
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
        if (files.length) {
            fileInput.files = files;
            uploadButton.style.display = 'block';
            dropZone.textContent = `Selected: ${files[0].name}`;
        }
    });

    // Click to upload
    dropZone.addEventListener('click', () => {
        fileInput.click();
    });

    fileInput.addEventListener('change', () => {
        if (fileInput.files.length) {
            uploadButton.style.display = 'block';
            dropZone.textContent = `Selected: ${fileInput.files[0].name}`;
        }
    });

    let modCounter = parseInt(localStorage.getItem('modCounter') || '0');
    
    function getNextModName() {
        modCounter++;
        localStorage.setItem('modCounter', modCounter.toString());
        return `mod${modCounter}.jar`;
    }

    function saveModMapping(originalName, modName) {
        const mappings = JSON.parse(localStorage.getItem('modMappings') || '{}');
        mappings[modName] = originalName;
        localStorage.setItem('modMappings', JSON.stringify(mappings));
    }

    uploadButton.addEventListener('click', async () => {
        try {
            if (!fileInput.files.length) {
                throw new Error('Please select a file first');
            }

            const selectedVersion = versionSelect.value;
            if (!selectedVersion) {
                throw new Error('Please select a version');
            }

            const originalFile = fileInput.files[0];
            const newModName = getNextModName();
            const renamedFile = new File([originalFile], newModName, {
                type: originalFile.type
            });

            // Save mapping of original name to new name
            saveModMapping(originalFile.name, newModName);

            progressBar.style.display = 'block';
            progressBar.value = 25;
            progressText.textContent = 'Uploading...';

            const formData = new FormData();
            formData.append('file', renamedFile);
            formData.append('targetVersion', selectedVersion);

            console.log('Uploading file:', {
                originalName: originalFile.name,
                newName: newModName,
                targetVersion: selectedVersion
            });
            
            const uploadResponse = await fetch("https://modupdater.onrender.com/api/upload", {
                method: "POST",
                body: formData,
                mode: 'cors',
                credentials: 'include',
                headers: {
                    'Accept': '*/*',
                    'Origin': window.location.origin,
                }
            });

            if (!uploadResponse.ok) {
                throw new Error(`Upload failed: ${uploadResponse.statusText}`);
            }

            const result = await uploadResponse.json();
            console.log("Process successful!", result);

            progressBar.value = 100;
            progressText.textContent = 'Complete!';
            
            const resultDiv = document.getElementById("result");
            if (resultDiv) {
                resultDiv.innerHTML = `
                    <h3>Process Complete!</h3>
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
            if (progressText) {
                progressText.textContent = `Error: ${error.message}`;
            }
            if (progressBar) {
                progressBar.classList.add('error');
            }
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


    async function fetchLogs() {
        try {
            const response = await fetch('https://modupdater.onrender.com/api/logs/version-comparison', {
                method: 'GET',
                headers: {
                    'Accept': 'application/json',
                    'Content-Type': 'application/json',
                    'Origin': 'https://goldfromgoldwila.github.io'
                },
                mode: 'cors',
                credentials: 'omit'
            });
            
            if (!response.ok) {
                throw new Error(`HTTP error! status: ${response.status}`);
            }
            
            const data = await response.json();
            if (data.success) {
                console.log('Logs:', data.logs);
                console.log('Diff Report:', data.diffReport);
            }
        } catch (error) {
            console.error('Failed to fetch logs:', error);
            // Add more detailed error logging
            if (error instanceof TypeError) {
                console.error('Network error - server might be down or CORS might be misconfigured');
            }
            stopPolling();
        }
    }
    
    // Improved polling control
    let pollInterval = null;
    const POLL_INTERVAL = 5000; // 5 seconds
    const MAX_RETRIES = 3;
    let retryCount = 0;

    function startPolling() {
        if (!pollInterval) {
            fetchLogs(); // Initial fetch
            pollInterval = setInterval(async () => {
                try {
                    await fetchLogs();
                    retryCount = 0; // Reset retry count on successful fetch
                } catch (error) {
                    retryCount++;
                    console.error(`Fetch attempt ${retryCount} failed`);
                    if (retryCount >= MAX_RETRIES) {
                        console.error('Max retries reached, stopping polling');
                        stopPolling();
                    }
                }
            }, POLL_INTERVAL);
        }
    }

    function stopPolling() {
        if (pollInterval) {
            clearInterval(pollInterval);
            pollInterval = null;
            retryCount = 0;
        }
    }

    // Start polling when page loads
    document.addEventListener('DOMContentLoaded', startPolling);
});



async function downloadModFileDiff() {
    try {
        console.log('Initiating mod file diff download...');
        const response = await fetch('https://modupdater.onrender.com/api/logs/mod-file-diff', {
            method: 'GET',
            headers: {
                'Accept': '*/*'
            },
            mode: 'cors'
        });
        
        if (!response.ok) {
            throw new Error(`Download failed: ${response.status}`);
        }
        
        const blob = await response.blob();
        const filename = response.headers.get('content-disposition')
            ?.split('filename=')[1]?.replace(/"/g, '') || 'mod_diff_report.txt';
            
        const url = window.URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.style.display = 'none';
        a.href = url;
        a.download = filename;
        document.body.appendChild(a);
        a.click();
        
        setTimeout(() => {
            window.URL.revokeObjectURL(url);
            document.body.removeChild(a);
        }, 1000);
        
        console.log('Mod file diff download completed');
    } catch (error) {
        console.error('Error downloading mod file diff:', error);
        alert('Failed to download mod file diff. Please try again later.');
    }
}