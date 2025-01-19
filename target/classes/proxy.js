const express = require('express');
const cors = require('cors');
const multer = require('multer');
const path = require('path');

const app = express();

// Comprehensive CORS configuration
app.use(cors({
    origin: [
        'http://localhost:5500', 
        'http://127.0.0.1:5500',
        'https://goldfromgoldwila.github.io',
        'https://modupdater.onrender.com'
    ],
    methods: ['GET', 'POST', 'OPTIONS'],
    allowedHeaders: ['Content-Type']
}));

// Custom storage configuration for Multer
const storage = multer.diskStorage({
    destination: 'uploads/',
    filename: function(req, file, cb) {
        // Generate unique filename using timestamp
        const uniqueName = `mod_${Date.now()}${path.extname(file.originalname)}`;
        cb(null, uniqueName);
    }
});

// Multer configuration with custom storage
const upload = multer({ 
    storage: storage,
    limits: {
        fileSize: 50 * 1024 * 1024 // 50 MB limit
    }
});

// Health check endpoint
app.get('/api/health', (req, res) => {
    res.status(200).json({ status: 'Server is running' });
});

// File upload endpoint
app.post('/api/upload', upload.single('file'), (req, res) => {
    try {
        if (!req.file) {
            return res.status(400).json({ error: 'No file uploaded' });
        }
        
        res.status(200).json({
            message: 'File uploaded successfully',
            filename: req.file.filename,
            originalName: req.file.originalname
        });
    } catch (error) {
        console.error('Upload error:', error);
        res.status(500).json({ error: 'File upload failed' });
    }
});

// Error handling middleware
app.use((err, req, res, next) => {
    console.error(err.stack);
    res.status(500).json({ error: 'Something went wrong!' });
});

const PORT = process.env.PORT || 8080;
app.listen(PORT, () => {
    console.log(`Server running on port ${PORT}`);
});