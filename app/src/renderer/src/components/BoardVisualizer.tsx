import { useEffect, useRef } from 'react'
import * as THREE from 'three'

interface BoardVisualizerProps {
  quat: [number, number, number, number] // [w, x, y, z]
}

export function BoardVisualizer({ quat }: BoardVisualizerProps) {
  const containerRef = useRef<HTMLDivElement | null>(null)
  const quatRef = useRef<[number, number, number, number]>(quat)

  // Sync prop changes to ref to avoid re-triggering useEffect
  useEffect(() => {
    quatRef.current = quat
  }, [quat])

  useEffect(() => {
    const container = containerRef.current
    if (!container) return

    const width = container.clientWidth
    const height = container.clientHeight

    // Scene
    const scene = new THREE.Scene()
    scene.background = new THREE.Color('#1e1e2e')

    // Camera
    const camera = new THREE.PerspectiveCamera(45, width / height, 0.1, 100)
    camera.position.set(4, 3, 5)
    camera.lookAt(0, 0, 0)

    // Renderer
    const renderer = new THREE.WebGLRenderer({ antialias: true })
    renderer.setSize(width, height)
    renderer.setPixelRatio(window.devicePixelRatio)
    container.appendChild(renderer.domElement)

    // Lights
    const ambientLight = new THREE.AmbientLight('#ffffff', 0.5)
    scene.add(ambientLight)

    const dirLight1 = new THREE.DirectionalLight('#ffffff', 0.8)
    dirLight1.position.set(5, 10, 7)
    scene.add(dirLight1)

    const dirLight2 = new THREE.DirectionalLight('#89b4fa', 0.3)
    dirLight2.position.set(-5, -5, -5)
    scene.add(dirLight2)

    // Grid helper
    const gridHelper = new THREE.GridHelper(8, 8, '#515c67', '#313244')
    gridHelper.position.y = -1.2
    scene.add(gridHelper)

    // Board Mesh (Xiao BLE Sense Board)
    // Board size: w=2.1, h=1.75, t=0.12 (from python mesh)
    const boardWidth = 2.1
    const boardHeight = 1.75
    const boardThickness = 0.12

    // Create PCB
    const pcbGeometry = new THREE.BoxGeometry(boardWidth, boardThickness, boardHeight)
    const pcbMaterial = new THREE.MeshStandardMaterial({
      color: '#1a5c24', // Nice dark green PCB color
      roughness: 0.2,
      metalness: 0.1
    })
    const boardGroup = new THREE.Group()
    const pcbMesh = new THREE.Mesh(pcbGeometry, pcbMaterial)
    boardGroup.add(pcbMesh)

    // Add some "gold" contact pads along the long edges for realism
    const padMaterial = new THREE.MeshStandardMaterial({
      color: '#f9e2af', // Gold color
      metalness: 0.8,
      roughness: 0.2
    })
    
    // Add pads
    const numPads = 7
    const padWidth = 0.12
    const padHeight = 0.02
    const padDepth = 0.2
    const startX = -boardWidth / 2 + 0.25
    const stepX = (boardWidth - 0.5) / (numPads - 1)

    for (let i = 0; i < numPads; i++) {
      const x = startX + i * stepX
      // Left side pads
      const padLeftGeom = new THREE.BoxGeometry(padWidth, padHeight, padDepth)
      const padLeft = new THREE.Mesh(padLeftGeom, padMaterial)
      padLeft.position.set(x, boardThickness / 2 + 0.005, -boardHeight / 2 + 0.1)
      boardGroup.add(padLeft)

      // Right side pads
      const padRight = padLeft.clone()
      padRight.position.z = boardHeight / 2 - 0.1
      boardGroup.add(padRight)
    }

    // Add main chip (MCU)
    const mcuGeometry = new THREE.BoxGeometry(0.6, 0.08, 0.6)
    const mcuMaterial = new THREE.MeshStandardMaterial({
      color: '#11111b', // Dark metal chip
      roughness: 0.5,
      metalness: 0.5
    })
    const mcu = new THREE.Mesh(mcuGeometry, mcuMaterial)
    mcu.position.set(-0.3, boardThickness / 2 + 0.04, 0)
    boardGroup.add(mcu)

    // Add IMU Sensor
    const imuGeometry = new THREE.BoxGeometry(0.25, 0.05, 0.25)
    const imuMaterial = new THREE.MeshStandardMaterial({
      color: '#313244',
      roughness: 0.6,
      metalness: 0.4
    })
    const imu = new THREE.Mesh(imuGeometry, imuMaterial)
    imu.position.set(0.3, boardThickness / 2 + 0.025, -0.25)
    boardGroup.add(imu)

    // Add USB-C connector
    const usbGeometry = new THREE.BoxGeometry(0.4, 0.15, 0.45)
    const usbMaterial = new THREE.MeshStandardMaterial({
      color: '#cdd6f4', // Silver metal
      roughness: 0.1,
      metalness: 0.9
    })
    const usb = new THREE.Mesh(usbGeometry, usbMaterial)
    usb.position.set(-boardWidth / 2 - 0.05, 0, 0)
    boardGroup.add(usb)

    scene.add(boardGroup)

    // Axes helper
    const axesHelper = new THREE.AxesHelper(1.5)
    // Position it at the center of the board
    boardGroup.add(axesHelper)

    // Resize Handler
    const handleResize = (): void => {
      const w = container.clientWidth
      const h = container.clientHeight
      camera.aspect = w / h
      camera.updateProjectionMatrix()
      renderer.setSize(w, h)
    }
    window.addEventListener('resize', handleResize)

    // Animation Loop
    let animationId: number
    const animate = (): void => {
      // Get latest quaternion
      const [w, x, y, z] = quatRef.current

      // Update orientation
      // Madgwick outputs NWU quaternion [w, x, y, z].
      // In Three.js coordinates, we set quaternion of the board mesh:
      // Note: we swap axes to align Three.js coordinate system (Y-up, X-right, Z-out)
      // with the IMU NWU orientation.
      // Usually, Three.js expects: x, y, z, w.
      // Let's set it directly and apply rotation offsets if needed.
      const q = new THREE.Quaternion(x, y, z, w)
      boardGroup.quaternion.copy(q)

      // Slow rotation for background effect if no data is arriving
      if (w === 1.0 && x === 0.0 && y === 0.0 && z === 0.0) {
        boardGroup.rotation.y += 0.005
        boardGroup.rotation.x = 0.3
      }

      renderer.render(scene, camera)
      animationId = requestAnimationFrame(animate)
    }

    animate()

    return () => {
      window.removeEventListener('resize', handleResize)
      cancelAnimationFrame(animationId)
      renderer.dispose()
      if (container.contains(renderer.domElement)) {
        container.removeChild(renderer.domElement)
      }
    }
  }, [])

  return (
    <div className="flex flex-col h-full rounded-xl bg-[#181825]/60 border border-[#313244]/50 backdrop-blur-md overflow-hidden">
      <div className="flex items-center justify-between px-4 py-2 bg-[#1e1e2e]/40 border-b border-[#313244]/40">
        <h3 className="text-xs font-semibold uppercase tracking-wider text-[#a6adc8]">3D Board Orientation</h3>
        <div className="flex gap-2">
          <span className="text-[10px] font-mono text-[#89b4fa] bg-[#89b4fa]/10 px-2 py-0.5 rounded border border-[#89b4fa]/20">NWU AHRS</span>
        </div>
      </div>
      <div className="flex-1 min-h-[300px] relative" ref={containerRef} />
    </div>
  )
}
