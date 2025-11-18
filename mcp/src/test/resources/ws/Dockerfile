# Use a Node.js base image
FROM node:14

# Set the working directory inside the container
WORKDIR /usr/src/app

# Copy the server.js file into the container
COPY server.js /usr/src/app/

# Install dependencies (e.g., the ws package)
RUN npm init -y && npm install ws

# Expose the port for WebSocket (e.g., 8080)
EXPOSE 8080

# Command to run the WebSocket server
CMD ["node", "server.js"]
