$url = "https://storage.googleapis.com/mediapipe-tasks/pose_landmarker/pose_landmarker_lite.task"
$output = "app/src/main/assets/pose_landmarker_lite.task"

Write-Host "Downloading MediaPipe pose landmarker model..."
Invoke-WebRequest -Uri $url -OutFile $output
Write-Host "Model downloaded successfully to $output"
