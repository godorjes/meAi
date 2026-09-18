# 对截图做像素采样，统计不同颜色数，用来客观判断某块区域是不是空白/没渲染。
# 空白页通常只有 3~13 色；正常界面通常数百色。
#
# 用法：
#   powershell -File scripts/pixel-check.ps1 <png>                       # 整张图
#   powershell -File scripts/pixel-check.ps1 <png> -X0 0 -Y0 142 -X1 788 -Y1 2361 -Step 8
param(
  [Parameter(Mandatory=$true)][string]$Path,
  [int]$X0 = 0,
  [int]$Y0 = 0,
  [int]$X1 = -1,
  [int]$Y1 = -1,
  [int]$Step = 8
)
Add-Type -AssemblyName System.Drawing
$img = [System.Drawing.Bitmap]::FromFile((Resolve-Path $Path).Path)
if ($X1 -lt 0) { $X1 = $img.Width }
if ($Y1 -lt 0) { $Y1 = $img.Height }
$colors = @{}
$n = 0
$sumR = 0; $sumG = 0; $sumB = 0
for ($x = $X0; $x -lt $X1; $x += $Step) {
  for ($y = $Y0; $y -lt $Y1; $y += $Step) {
    $c = $img.GetPixel($x, $y)
    $colors["$($c.R),$($c.G),$($c.B)"] = $true
    $sumR += $c.R; $sumG += $c.G; $sumB += $c.B
    $n++
  }
}
$avgR = [int]($sumR / $n); $avgG = [int]($sumG / $n); $avgB = [int]($sumB / $n)
Write-Output ("region [{0},{1}]-[{2},{3}] step={4}px samples={5} distinctColors={6} avgRGB=({7},{8},{9})" -f $X0,$Y0,$X1,$Y1,$Step,$n,$colors.Count,$avgR,$avgG,$avgB)
$img.Dispose()
