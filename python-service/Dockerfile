# python-service/Dockerfile

# Use an official Python runtime as a parent image
# Using slim reduces image size. Choose a version Vanya/Andrew are comfortable with (e.g., 3.10 or 3.11)
FROM python:3.10-slim

# Set environment variables
ENV PYTHONUNBUFFERED True
# Set the listening port (Cloud Run expects 8080 by default)
ENV PORT 8080

# Create a non-root user and group
RUN groupadd --system --gid 1001 appgroup && \
    useradd --system --uid 1001 --gid appgroup appuser

# Set the working directory in the container
WORKDIR /app

# Copy the requirements file into the container at /app
COPY requirements.txt .

# Install any needed packages specified in requirements.txt
# --no-cache-dir reduces image size. --system is important if system libraries like GDAL are needed later.
# Vanya/Andrew may need to add commands here later to install system deps like libgdal-dev before pip install
RUN pip install --no-cache-dir -r requirements.txt

# Copy the rest of the application code into the container at /app
COPY . .

# Switch to the non-root user
USER appuser

# Run app.py using gunicorn when the container launches
# Listen on all interfaces (0.0.0.0) on the specified port
CMD exec gunicorn --bind :${PORT} --workers 1 --threads 8 --timeout 0 app:app
# Note: Adjust workers/threads based on performance testing later
# Note: timeout 0 might be needed for long-running ML model loads/inferences