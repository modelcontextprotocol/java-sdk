// Import the WebSocket package
const WebSocket = require('ws');

// Set up the WebSocket server to listen on port 8080
const wss = new WebSocket.Server({ port: 8080 });

// When a new WebSocket connection is established
wss.on('connection', function connection(ws) {
    console.log('New client connected');

    // When a message is received from the client
    ws.on('message', function incoming(message) {
        console.log('received: %s', message);
    });

    // Send a welcome message to the client
    ws.send('Welcome to the WebSocket server!');
});

// Log the WebSocket server start
console.log('WebSocket server is listening on ws://localhost:8080');
