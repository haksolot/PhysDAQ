import { useEffect, useRef } from 'react'
import * as THREE from 'three'
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card'

interface BoardVisualizerProps {
  quat: [number, number, number, number] // [w, x, y, z]
}

export function BoardVisualizer({ quat }: BoardVisualizerProps) {
  const containerRef = useRef<HTMLDivElement | null>(null)
  const quatRef = useRef<[number, number, number, number]>(quat)
  const idleAngle = useRef<number>(0)

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

    // Create PCB (Z-up coordinate system: X=width, Y=height, Z=thickness)
    const pcbGeometry = new THREE.BoxGeometry(boardWidth, boardHeight, boardThickness)
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
      const padLeftGeom = new THREE.BoxGeometry(padWidth, padDepth, padHeight)
      const padLeft = new THREE.Mesh(padLeftGeom, padMaterial)
      padLeft.position.set(x, -boardHeight / 2 + 0.1, boardThickness / 2 + 0.01)
      boardGroup.add(padLeft)

      // Right side pads
      const padRight = padLeft.clone()
      padRight.position.y = boardHeight / 2 - 0.1
      boardGroup.add(padRight)
    }

    // Add main chip (MCU)
    const mcuGeometry = new THREE.BoxGeometry(0.6, 0.6, 0.08)
    const mcuMaterial = new THREE.MeshStandardMaterial({
      color: '#11111b', // Dark metal chip
      roughness: 0.5,
      metalness: 0.5
    })
    const mcu = new THREE.Mesh(mcuGeometry, mcuMaterial)
    mcu.position.set(-0.3, 0, boardThickness / 2 + 0.04)
    boardGroup.add(mcu)

    // Add IMU Sensor
    const imuGeometry = new THREE.BoxGeometry(0.25, 0.25, 0.05)
    const imuMaterial = new THREE.MeshStandardMaterial({
      color: '#313244',
      roughness: 0.6,
      metalness: 0.4
    })
    const imu = new THREE.Mesh(imuGeometry, imuMaterial)
    imu.position.set(0.3, -0.25, boardThickness / 2 + 0.025)
    boardGroup.add(imu)

    // Add USB-C connector
    const usbGeometry = new THREE.BoxGeometry(0.4, 0.45, 0.15)
    const usbMaterial = new THREE.MeshStandardMaterial({
      color: '#cdd6f4', // Silver metal
      roughness: 0.1,
      metalness: 0.9
    })
    const usb = new THREE.Mesh(usbGeometry, usbMaterial)
    usb.position.set(-boardWidth / 2 - 0.05, 0, 0)
    boardGroup.add(usb)

    // Axes helper (Body axes, rotating with the board)
    const axesHelper = new THREE.AxesHelper(1.5)
    boardGroup.add(axesHelper)

    // Create a parent group to map Z-up (NWU) to Y-up (Three.js world)
    const parentGroup = new THREE.Group()
    parentGroup.rotation.x = -Math.PI / 2
    parentGroup.add(boardGroup)
    scene.add(parentGroup)

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

      if (w === 1.0 && x === 0.0 && y === 0.0 && z === 0.0) {
        // Slow idle rotation in Z-up
        idleAngle.current += 0.005
        const tiltQ = new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(1, 0, 0), 0.3)
        const rotQ = new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(0, 0, 1), idleAngle.current)
        boardGroup.quaternion.copy(tiltQ.multiply(rotQ))
      } else {
        // Set quaternion directly in NWU space
        const q = new THREE.Quaternion(x, y, z, w)
        boardGroup.quaternion.copy(q)
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
    <Card className="flex flex-col h-full bg-card/60 backdrop-blur-md overflow-hidden border !p-0 !gap-0">
      <CardHeader className="flex flex-row items-center justify-between !px-4 !py-3 space-y-0 border-b bg-muted/20 !pb-3">
        <CardTitle className="text-xs font-semibold uppercase tracking-wider text-muted-foreground animate-none">3D Board Orientation</CardTitle>
        <span className="text-[10px] font-mono text-primary bg-primary/10 px-2 py-0.5 rounded border border-primary/20">
          NWU AHRS
        </span>
      </CardHeader>
      <CardContent className="flex-1 min-h-[200px] relative !p-0" ref={containerRef} />
    </Card>
  )
}
