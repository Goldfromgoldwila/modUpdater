document.getElementById("uploadForm").addEventListener("submit", async (event) => {
    event.preventDefault();
    
    const selectedVersion = document.getElementById("mc-version").value;

    try {
    
        // Add a loading message
        console.log("File uploaded! Please wait 5 seconds for processing...");

        // Add 5 second delay
        await new Promise(resolve => setTimeout(resolve, 3500));

        // Then convert
        const convertResponse = await fetch("https://modupdater.onrender.com/api/convert", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ version: selectedVersion })
        });

        if (!convertResponse.ok) {
            throw new Error(`Conversion failed: ${convertResponse.statusText}`);
        }

        const result = await convertResponse.json();
        console.log("Success:", result);

    } catch (error) {
        console.error("Error:", error);
    }
});