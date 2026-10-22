import grpc
import sys

# Test gRPC client against local azookey server pipe
# But in Windows, named pipe gRPC requires special transport or we can test using our benchmark script
print("Testing live AI assist candidates...")
