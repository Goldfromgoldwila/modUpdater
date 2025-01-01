document.getElementById('uploadForm').addEventListener('submit', async (e) => {
    e.preventDefault();

    const fileInput = document.getElementById('modFile');
    if (fileInput.files.length === 0) {
        console.error('No file selected for upload.');
        return;
    }

    // Fetch or initialize file counter from localStorage
    let fileCount = parseInt(localStorage.getItem('modFileCount') || '0') + 1;
    localStorage.setItem('modFileCount', fileCount);

    // Get the selected file and rename it
    const file = fileInput.files[0];
    const fileExtension = file.name.split('.').pop();
    const newFileName = `mod${fileCount}.${fileExtension}`;

    console.log('Renaming File Details:', {
        originalName: file.name,
        newName: newFileName,
        size: file.size,
        type: file.type
    });

    // Create a new File object with the renamed filename
    const renamedFile = new File([file], newFileName, { type: file.type });

    // Prepare form data
    const formData = new FormData();
    formData.append('file', renamedFile);

    try {
        // Check server availability
        const healthCheck = await fetch('https://goldfromgoldwila.github.io/modUpdater/api/health', {
            method: 'GET',
            headers: {
                'Cache-Control': 'no-cache'
            }
        });

        if (!healthCheck.ok) {
            throw new Error('Server health check failed');
        }

        // Upload the file
        const response = await fetch('https://goldfromgoldwila.github.io/modUpdater/api/upload', {
            method: 'POST',
            body: formData,
        });

        // Log the raw response for debugging
        const textResponse = await response.text(); // Get the response as text
        console.log('Response from server:', textResponse); // Log the raw response

        // Check if the response is OK
        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}, message: ${textResponse}`);
        }

        // Since the response is plain text, we can log it directly
        console.log('File uploaded successfully:', textResponse);

        // Fetch logs after upload
        const logsResponse = await fetch('https://goldfromgoldwila.github.io/modUpdater/api/logs');
        if (logsResponse.ok) {
            const logs = await logsResponse.json(); // Parse logs response
            console.log('Server Logs:', logs);
        } else {
            console.error('Failed to fetch logs:', logsResponse.statusText);
        }

    } catch (error) {
        console.error('Upload error:', error);
    }
});